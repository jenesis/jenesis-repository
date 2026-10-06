package build.jenesis.repository.dependents;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.dependency.ArtifactSbom;
import build.jenesis.repository.dependency.DependencyComponent;
import build.jenesis.repository.dependency.DependencyGraph;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.DirtyIndexFeed;
import build.jenesis.repository.walk.ArtifactWalk;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.walk.WalkPass;
import build.jenesis.repository.walk.SegmentedPartial;

/**
 * The write side of the reverse-dependency ("who depends on X") index: the {@link DependentsIndexTask} sweep inverts
 * the CycloneDX dependency graph {@link ArtifactSbom} reads out of every stored blob into the sharded index
 * {@link DependentsQueryReader} reads back, through the shared {@link DependentsStore} layout. Each shard is written by
 * compare-and-set, so replicas converge, and a full rebuild recomputes and re-commits the whole set, so an artifact
 * deleted since drops out; the steady state applies only the changed blobs. Its read-back methods delegate to the
 * reader, so a sweep verifies what it wrote through the query path.
 *
 * <p>Each artifact is streamed through {@link ArtifactSbom#graph}, materialising only the embedded BOM. With the shared
 * {@link ArtifactWalk} the rebuild is a resumable, range-segmented {@code walks/dependents} pass. Each segment persists
 * its partial inversion as a compare-and-set HEAD ({@code walks/dependents/state/<nnn>/head}: generation, holder,
 * cursor, chunk count) and immutable append chunks ({@code .../g<gen>/c<n>}): before each cursor commit only the edges
 * new since the last checkpoint are written as the next chunk, so a segment's write cost is linear in its size. A
 * crash resumes from the committed cursor with every committed chunk intact, and another node can take a dead
 * worker's segment over from it. Because a coordinate collects edges from every segment, the pass-completion merge -
 * idempotent, run by every finisher, guarded by generation, and repeated before the next walk if a crash lost it -
 * unions the chunks into the same shard commit {@link #commit} makes. Without a walk, the rebuild is the complete
 * recompute under the sweep's lease.
 */
public final class DependentsIndex {

    private static final Logger LOGGER = LoggerFactory.getLogger(DependentsIndex.class);

    /** How many of the 256 shard bytes one merge or recompute band covers - the bound on its heap footprint. */
    private static final int MERGE_SHARD_BATCH = 16;

    private final ArtifactStore store;
    private final ArtifactWalk walk;

    /** The read path over the same store, which read-backs go through. */
    private final DependentsQueryReader reader;

    public DependentsIndex(ArtifactStore store) {
        this(store, null);
    }

    /** A sweep-owned index whose rebuild rides the shared walk as a resumable, segmented, multi-node pass; hand
     *  this to the scheduled sweep only - the query surfaces read shards and never need a walk. */
    public DependentsIndex(ArtifactStore store, ArtifactWalk walk) {
        this.store = store;
        this.walk = walk;
        this.reader = new DependentsQueryReader(store);
    }

    /**
     * The coordinates that depend on {@code coordinate}, read back through {@link DependentsQueryReader}.
     */
    public List<String> dependents(String coordinate) throws IOException {
        return reader.dependents(coordinate);
    }

    /** Every coordinate the index holds a dependent for, sorted - a read-back through the query path. */
    public List<String> coordinates() throws IOException {
        return reader.coordinates();
    }

    /** The subset of {@code coordinates} the index holds a dependent for - the bounded reachability probe, a
     *  read-back through the query path (a direct read of the shards the query set addresses). */
    public Set<String> reachable(Collection<String> coordinates) throws IOException {
        return reader.reachable(coordinates);
    }

    /** Whether a sweep has committed this index at least once - a read-back through the query path. */
    public boolean built() throws IOException {
        return reader.built();
    }

    /** The instant the last completing sweep rebuilt this index - a read-back through the query path. */
    public Optional<Instant> builtAt() throws IOException {
        return reader.builtAt();
    }

    /**
     * Recompute the whole index from the repository's stored blobs and commit it, returning the number of live
     * shards written. Each blob is streamed through {@link ArtifactSbom#graph} - only a jar carrying a CycloneDX
     * SBOM contributes, and one unreadable blob is skipped rather than derailing the sweep - and its root
     * artifact is recorded as a dependent of every coordinate in its resolved tree.
     *
     * <p>Walk-less this is the complete recompute under the lease. With the walk it joins or starts the segmented
     * pass, and while other holders still walk their segments it returns the live shard count, the finisher
     * committing.
     */
    public int rebuild() throws IOException {
        if (walk == null) {
            return rebuildSharded();
        }
        Optional<WalkPass> previous = walk.pass(store, DependentsStore.PASS).filter(WalkPass::complete);
        if (previous.isPresent() && previous.get().generation() > lastMerged()) {
            // A crash lost only the merge; the partials remain, so a completed, unmerged pass is merged first.
            merge(previous.get().generation());
        }
        WalkPass pass = walk.walk(store, DependentsStore.PASS, List.of("blobs"), new InvertingVisitor());
        if (!pass.complete()) {
            return shards();                                    // other holders still walk; the last finisher merges
        }
        OptionalInt committed = merge(pass.generation());
        return committed.isPresent() ? committed.getAsInt() : shards();  // empty: superseded before the merge
    }

    /**
     * The walk-less full recompute, a {@link #MERGE_SHARD_BATCH}-wide band of shard bytes at a time: each band re-pages
     * {@code blobs/}, keeps only the edges whose shard falls in it and writes those shards, so the heap holds one
     * band, at the cost of re-reading the blobs per band - the trade {@link #mergeSharded} makes. Compaction and the
     * {@link DependentsStore#BUILT} stamp match {@link #commit}. Returns the live shard count.
     */
    private int rebuildSharded() throws IOException {
        Set<String> filled = new TreeSet<>();
        for (int base = 0; base < 256; base += MERGE_SHARD_BATCH) {
            String from = String.format(Locale.ROOT, "%02x", base);
            String to = String.format(Locale.ROOT, "%02x", Math.min(255, base + MERGE_SHARD_BATCH - 1));
            Map<String, SortedSet<String>> band = new TreeMap<>();
            invertPagedBlobs(band, from, to);
            Map<String, Map<String, Collection<String>>> byShard = new TreeMap<>();
            band.forEach((coordinate, dependents) -> {
                if (!dependents.isEmpty()) {
                    byShard.computeIfAbsent(DependentsStore.shard(coordinate), _ -> new TreeMap<>())
                            .put(coordinate, dependents);
                }
            });
            for (Map.Entry<String, Map<String, Collection<String>>> shard : byShard.entrySet()) {
                write(DependentsStore.PREFIX + "/" + shard.getKey(), DependentsStore.serialise(shard.getValue()));
                filled.add(shard.getKey());
            }
        }
        for (String existing : store.list(DependentsStore.PREFIX)) {
            if (DependentsStore.isShard(existing) && !filled.contains(existing)) {
                store.delete(DependentsStore.PREFIX + "/" + existing); // compaction: drop an emptied shard (never the marker)
            }
        }
        write(DependentsStore.BUILT, DependentsStore.builtMarker(Instant.now())); // stamp built even at
        // zero shards (with the completion instant, so staleness is visible)
        return filled.size();
    }

    /**
     * The steady-state sweep: drains the {@link DirtyIndexFeed} the {@link DependentsPublicationObserver} marks and
     * applies only the changed blobs. Returns {@code false} when no index is built yet, for the caller to run the
     * bootstrap {@link #rebuild()}.
     *
     * <p>The feed is drained {@link #APPLY_BATCH} markers at a time, each batch rewriting every touched shard once
     * ({@link #applyBatch}). An added blob's SBOM is parsed through {@link #invert}; a contributed-edges record
     * ({@code dependents/edges/<hash>}) is written beside it, so a later removal undoes exactly those edges and then
     * deletes the record. Markers are cleared token-fenced after their batch, so a re-touched blob survives and a crash
     * replays the batch idempotently. Shard lines carry no version, so ordering leans on the feed's newest-per-blob
     * coalescing, and the reconcile heals any drift.
     */
    boolean applyIncremental() throws IOException {
        if (!built()) {
            return false;   // no index yet - the caller runs the bootstrap full rebuild that stamps the built marker
        }
        DirtyIndexFeed feed = new DirtyIndexFeed(store, DependentsStore.PREFIX);
        feed.drain(APPLY_BATCH, page -> {
            applyBatch(page);
            feed.clear(page);       // each page's shards have committed: cleared as it goes
        });
        return true;                // an empty feed is nothing changed since the last sweep - the shards stand
    }

    /** Dirty markers applied per batch, bounding its heap however long the feed is. */
    static final int APPLY_BATCH = 10_000;

    /**
     * The reconcile: the full {@link #rebuild()} from the blobs, then the feed compacted through a cutoff captured
     * before the rebuild read anything, so a change marked meanwhile survives for the next incremental sweep. A
     * dropped marker loses no edge, since the rebuild re-derives it. Returns the live shard count.
     */
    int reconcile() throws IOException {
        DirtyIndexFeed feed = new DirtyIndexFeed(store, DependentsStore.PREFIX);
        long cutoff = System.currentTimeMillis();
        int shards = rebuild();
        feed.compactThrough(cutoff);
        return shards;
    }

    /**
     * Applies one batch of dirty blobs with each touched shard written once; a rewrite per blob would be quadratic
     * in the blobs sharing a shard. Removals precede additions, so a root a batch both removes and adds ends present.
     * A blob inverting to no edges writes no record, and a removed blob with no record changes nothing.
     */
    private void applyBatch(List<DirtyIndexFeed.Entry> batch) throws IOException {
        Map<String, Map<String, Set<String>>> additions = new TreeMap<>();   // shard -> coordinate -> roots to union
        Map<String, Map<String, Set<String>>> removals = new TreeMap<>();    // shard -> coordinate -> roots to drop
        Map<String, byte[]> records = new LinkedHashMap<>();                  // the contributed-edges record per added blob
        List<String> dropped = new ArrayList<>();                             // the records of the removed blobs
        for (DirtyIndexFeed.Entry entry : batch) {
            String hash = entry.coordinate();
            if (entry.removed()) {
                Optional<ArtifactStore.Versioned> stored = store.readVersioned(DependentsStore.edgesKey(hash));
                if (stored.isEmpty()) {
                    continue;
                }
                DependentsStore.ContributedEdges record = DependentsStore.ContributedEdges.parse(stored.get().content());
                if (record != null) {
                    for (String coordinate : record.dependencies()) {
                        collect(removals, coordinate, record.root());
                    }
                }
                dropped.add(DependentsStore.edgesKey(hash));
            } else {
                Map<String, SortedSet<String>> edges = new TreeMap<>();
                invert("blobs/" + hash, edges);
                if (edges.isEmpty()) {
                    continue;
                }
                String root = edges.values().iterator().next().first();   // one blob, one root - every value is {root}
                for (String coordinate : edges.keySet()) {
                    collect(additions, coordinate, root);
                }
                records.put(DependentsStore.edgesKey(hash), DependentsStore.serialiseEdges(root, edges.keySet()));
            }
        }
        Set<String> shards = new TreeSet<>(removals.keySet());
        shards.addAll(additions.keySet());
        for (String shardByte : shards) {
            DependentsStore.rewriteShard(store, DependentsStore.PREFIX + "/" + shardByte,
                    removals.getOrDefault(shardByte, Map.of()), additions.getOrDefault(shardByte, Map.of()));
        }
        for (Map.Entry<String, byte[]> record : records.entrySet()) {
            write(record.getKey(), record.getValue());
        }
        for (String key : dropped) {
            store.delete(key);
        }
    }

    /** File {@code root} under {@code coordinate} in the shard the coordinate hashes to. */
    private static void collect(Map<String, Map<String, Set<String>>> into, String coordinate, String root) {
        into.computeIfAbsent(DependentsStore.shard(coordinate), _ -> new TreeMap<>())
                .computeIfAbsent(coordinate, _ -> new TreeSet<>()).add(root);
    }

    /** Inverts every {@code blobs/} key a page at a time, never holding the namespace as one list. */
    private void invertPagedBlobs(Map<String, SortedSet<String>> inverted) throws IOException {
        invertPagedBlobs(inverted, null, null);
    }

    /** As above, retaining only the coordinates whose shard byte is in [{@code from}, {@code to}] (both {@code null}
     *  retains all) - {@link #rebuildSharded}'s pass per band. */
    private void invertPagedBlobs(Map<String, SortedSet<String>> inverted, String from, String to) throws IOException {
        BLOBS.scan(store, "blobs", hash -> invert("blobs/" + hash, inverted, from, to));
    }

    /** The {@code blobs/} enumeration. A rebuild that stopped early would publish an index missing edges, so there is
     *  no entry cap; the bound is 10^5 round-trips of 512 names, past which it raises a {@code TraversalException}. */
    private static final BoundedChildren BLOBS =
            BoundedChildren.bounded().entries(Integer.MAX_VALUE).steps(100_000).page(512);

    /** Inverts one blob's embedded SBOM into {@code into}: its root recorded as a dependent of every coordinate in its
     *  tree. A blob read to its end with no usable SBOM, or whose SBOM cannot be parsed safely, is negative-cached - a
     *  blob is immutable, so that is permanent - and skipped unopened thereafter. A read failing part way is not
     *  cached: it is skipped this pass and retried, so a transient reset never drops an artifact's edges. */
    private void invert(String key, Map<String, SortedSet<String>> into) throws IOException {
        invert(key, into, null, null);
    }

    /** {@link #invert(String, Map)}, retaining only the coordinates whose shard byte is in [{@code from}, {@code to}]
     *  (both {@code null} retains all). */
    private void invert(String key, Map<String, SortedSet<String>> into, String from, String to) throws IOException {
        String negative = DependentsStore.negativeKey(key);
        if (negative != null && store.exists(negative)) {
            return;                                             // an immutable blob known to carry no SBOM
        }
        DependencyGraph graph;
        try (InputStream blob = store.open(key)) {
            Optional<DependencyGraph> parsed;
            try {
                parsed = ArtifactSbom.graph(blob);
            } catch (StackOverflowError | RuntimeException failure) {
                // A parse fault the parser's own caps did not catch: skipped and negative-cached, since an immutable
                // blob's fault is permanent, and logged. Scoped to the parse alone, and to StackOverflowError rather
                // than every Error, so fatal VM faults still propagate.
                LOGGER.warn(
                        "skipping blob " + key + " - its embedded SBOM could not be parsed safely; negative-caching it "
                                + "so the next sweep does not re-hit it", failure);
                rememberNoSbom(negative);
                return;
            }
            if (parsed.isEmpty()) {
                // Read to its end with no usable SBOM: permanent, so negative-cached.
                rememberNoSbom(negative);
                return;
            }
            graph = parsed.get();
        } catch (IOException failure) {
            // A read failing part way is transient: not negative-cached, retried next sweep, and logged.
            LOGGER.warn(
                    "skipping blob " + key + " this pass - its read failed part way through, so it is retried on the "
                            + "next sweep rather than negative-cached as carrying no SBOM", failure);
            return;
        }
        Optional<DependencyComponent> root = graph.root();
        if (root.isEmpty()) {
            return;                                             // no root component - no dependent to attribute to
        }
        String dependent = root.get().coordinate();
        if (dependent.isBlank()) {
            return;
        }
        for (DependencyComponent dependency : graph.dependencies()) {
            String coordinate = dependency.coordinate();
            if (coordinate.isBlank() || coordinate.equals(dependent)) {
                continue;
            }
            if (from != null) {
                String shard = DependentsStore.shard(coordinate);
                if (shard.compareTo(from) < 0 || shard.compareTo(to) > 0) {
                    continue;                                   // out of this rebuild band - a later band records it
                }
            }
            into.computeIfAbsent(coordinate, _ -> new TreeSet<>()).add(dependent);
        }
    }

    /**
     * Unions the partial inversions of the completed pass {@code generation} and commits them as {@link #commit}
     * would. Each segment contributes its committed chunks {@code c0..c(chunkCount-1)}; an orphan chunk a crash left
     * at {@code chunkCount} is never read. Idempotent, so every finisher may run it, and guarded by generation, so a
     * merge superseded by the next pass writes nothing. Earlier passes' partials are deleted afterwards - never this
     * one's, which a racing finisher may be reading. Returns the live shard count, or empty when superseded.
     */
    private OptionalInt merge(long generation) throws IOException {
        Optional<WalkPass> current = walk.pass(store, DependentsStore.PASS);
        if (current.isEmpty() || current.get().generation() != generation) {
            return OptionalInt.empty();
        }
        // The committed chunk keys only; mergeSharded reads the bodies a shard band at a time.
        List<String> chunkKeys = new ArrayList<>();
        for (int index = 0; index < current.get().segments(); index++) {
            Optional<ArtifactStore.Versioned> stored = store.readVersioned(DependentsStore.headKey(index));
            DependentsStore.SegmentState head =
                    stored.map(versioned -> DependentsStore.SegmentState.parseHead(versioned.content())).orElse(null);
            if (head == null || head.generation() != generation) {
                continue;                                       // a segment that held no blob never wrote a HEAD
            }
            for (int chunk = 0; chunk < head.chunkCount(); chunk++) {
                chunkKeys.add(DependentsStore.chunkKey(index, generation, chunk));
            }
        }
        int shards = mergeSharded(chunkKeys);
        recordMerged(generation);                               // this pass is merged - the next rebuild won't re-merge it
        cleanupSuperseded(generation);
        return OptionalInt.of(shards);
    }

    /**
     * Commits the completed pass's chunks into the shard set a {@link #MERGE_SHARD_BATCH}-wide band at a time,
     * re-reading the chunks per band, so the finisher's heap holds one band rather than the whole graph. Compaction
     * and the {@link DependentsStore#BUILT} marker are applied as {@link #commit} does. Returns the live shard count.
     */
    private int mergeSharded(List<String> chunkKeys) throws IOException {
        Set<String> filled = new TreeSet<>();
        for (int base = 0; base < 256; base += MERGE_SHARD_BATCH) {
            String from = String.format(Locale.ROOT, "%02x", base);
            String to = String.format(Locale.ROOT, "%02x", Math.min(255, base + MERGE_SHARD_BATCH - 1));
            Map<String, Map<String, Collection<String>>> byShard = new TreeMap<>();
            for (String chunkKey : chunkKeys) {
                Optional<ArtifactStore.Versioned> body = store.readVersioned(chunkKey);
                if (body.isEmpty()) {
                    continue;                                   // should-never: chunkCount only advances after a chunk lands
                }
                DependentsStore.SegmentState.parseChunk(body.get().content()).forEach((coordinate, dependents) -> {
                    if (dependents.isEmpty()) {
                        return;
                    }
                    String shard = DependentsStore.shard(coordinate);
                    if (shard.compareTo(from) >= 0 && shard.compareTo(to) <= 0) {
                        byShard.computeIfAbsent(shard, _ -> new TreeMap<>())
                                .computeIfAbsent(coordinate, _ -> new TreeSet<>()).addAll(dependents);
                    }
                });
            }
            for (Map.Entry<String, Map<String, Collection<String>>> shard : byShard.entrySet()) {
                write(DependentsStore.PREFIX + "/" + shard.getKey(), DependentsStore.serialise(shard.getValue()));
                filled.add(shard.getKey());
            }
        }
        for (String existing : store.list(DependentsStore.PREFIX)) {
            if (DependentsStore.isShard(existing) && !filled.contains(existing)) {
                store.delete(DependentsStore.PREFIX + "/" + existing); // compaction: drop an emptied shard (never the marker)
            }
        }
        write(DependentsStore.BUILT, DependentsStore.builtMarker(Instant.now())); // stamp built (with the
        // completion instant, so staleness is visible) even when zero shards were filled
        return filled.size();
    }

    /** Deletes the HEADs and chunks of passes before {@code generation}, never this one's; an unparseable HEAD counts
     *  as superseded. */
    private void cleanupSuperseded(long generation) throws IOException {
        for (String segment : store.list(DependentsStore.PASS_STATE)) {
            String base = DependentsStore.PASS_STATE + "/" + segment;
            for (String entry : store.list(base)) {
                String path = base + "/" + entry;
                if (entry.equals(DependentsStore.HEAD)) {
                    DependentsStore.SegmentState head = store.readVersioned(path)
                            .map(versioned -> DependentsStore.SegmentState.parseHead(versioned.content())).orElse(null);
                    if (head == null || head.generation() < generation) {
                        store.delete(path);                     // a stale HEAD from a superseded (or torn) pass
                    }
                } else if (entry.startsWith("g")) {
                    long chunkGeneration = DependentsStore.parseGeneration(entry);
                    if (chunkGeneration >= 0 && chunkGeneration < generation) {
                        for (String chunk : store.list(path)) {
                            store.delete(path + "/" + chunk);   // drop a superseded generation's chunks
                        }
                    }
                }
            }
        }
    }

    /** The currently live shard count - what an ACTIVE pass's joiner reports while the eventual finisher merges. */
    private int shards() throws IOException {
        int count = 0;
        for (String child : store.list(DependentsStore.PREFIX)) {
            if (DependentsStore.isShard(child)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Commits an inverted {@code coordinate -> dependents} map: every shard it fills is written and every other is
     * deleted, so no stale entry survives, then the {@link DependentsStore#BUILT} marker is stamped, so even an empty
     * index reads as built.
     */
    int commit(Map<String, ? extends Collection<String>> inverted) throws IOException {
        Map<String, Map<String, Collection<String>>> byShard = new TreeMap<>();
        for (Map.Entry<String, ? extends Collection<String>> entry : inverted.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                byShard.computeIfAbsent(DependentsStore.shard(entry.getKey()), _ -> new TreeMap<>())
                        .put(entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<String, Map<String, Collection<String>>> shard : byShard.entrySet()) {
            write(DependentsStore.PREFIX + "/" + shard.getKey(), DependentsStore.serialise(shard.getValue()));
        }
        for (String existing : store.list(DependentsStore.PREFIX)) {
            if (DependentsStore.isShard(existing) && !byShard.containsKey(existing)) {
                store.delete(DependentsStore.PREFIX + "/" + existing); // compaction: drop an emptied shard (never the marker)
            }
        }
        write(DependentsStore.BUILT, DependentsStore.builtMarker(Instant.now())); // stamp built (with the
        // completion instant, so staleness is visible) even when zero shards were filled
        return byShard.size();
    }

    /** Records that a blob carries no SBOM; best-effort, so a failed write only re-checks the blob next pass. */
    private void rememberNoSbom(String negative) {
        if (negative == null) {
            return;
        }
        try {
            store.writeVersioned(negative, DependentsStore.NO_SBOM_MARKER, null);
        } catch (IOException _) {
            // best-effort negative cache; the blob is simply re-decompressed on the next pass
        }
    }

    /** The last pass generation merged; {@code 0} when none or unreadable, which only re-runs the idempotent merge. */
    private long lastMerged() throws IOException {
        Optional<ArtifactStore.Versioned> stored = store.readVersioned(DependentsStore.MERGED);
        if (stored.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(new String(stored.get().content(), StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException _) {
            return 0L;
        }
    }

    /** Advances the merged-generation marker, never backwards; best-effort, since a failure costs only a redundant
     *  merge. */
    private void recordMerged(long generation) throws IOException {
        Retries.tryUpdate(store, DependentsStore.MERGED, stored -> {
            long current = 0L;
            if (stored.isPresent()) {
                try {
                    current = Long.parseLong(new String(stored.get().content(), StandardCharsets.UTF_8).trim());
                } catch (NumberFormatException _) {
                    current = 0L;
                }
            }
            return current >= generation ? null : Long.toString(generation).getBytes(StandardCharsets.UTF_8);
        });
    }

    /** Compare-and-set a shard object into place, re-reading the token on a lost race (none under the lease). */
    private void write(String key, byte[] body) throws IOException {
        Retries.update(store, key, _ -> body);
    }

    /**
     * Inverts the walk's {@code blobs/} key stream into this worker's segment partial: the edges new since the last
     * checkpoint accumulate as a delta, which {@link #beforeCheckpoint} writes as the next immutable chunk before the
     * walk commits its cursor, then advances the segment HEAD's cursor and chunk count by one compare-and-set. Chunk
     * first, count second, so a crash leaves only an orphan the merge never reads. A worker taking a segment over
     * adopts the HEAD's cursor and count, skips keys at or below the cursor and appends from there. A flush that loses
     * the HEAD's compare-and-set proves the segment was taken over, and the worker stops.
     *
     * <p>Chunk {@code n} of a segment and generation always covers the same key interval - the walk redelivers the
     * same sequence and checkpoints at the same stride - so two holders computing it write identical bytes.
     */
    private final class InvertingVisitor extends SegmentedPartial {

        /** How many chunks this segment's HEAD has already committed - the next chunk this worker writes is c<count>. */
        private int chunkCount;
        /** The chunk count the flush in flight commits, adopted once its HEAD lands. */
        private int flushing;
        /** The edges new since the last checkpoint, flushed as the next chunk - bounded by one stride. */
        private final Map<String, SortedSet<String>> delta = new TreeMap<>();

        InvertingVisitor() {
            super(store, walk, DependentsStore.PASS, "dependents");
        }

        @Override
        protected String stateKey(int segment) {
            return DependentsStore.headKey(segment);
        }

        @Override
        protected String holderOf(byte[] state) {
            DependentsStore.SegmentState head = DependentsStore.SegmentState.parseHead(state);
            return head == null ? null : head.holder();
        }

        @Override
        protected void reset() {
            delta.clear();
            chunkCount = 0;
        }

        @Override
        protected String adopt(byte[] state, long generation) {
            DependentsStore.SegmentState head = DependentsStore.SegmentState.parseHead(state);
            if (head == null || head.generation() != generation) {
                return null;
            }
            chunkCount = head.chunkCount();
            return head.cursor();
        }

        @Override
        protected boolean fold(String key) throws IOException {
            invert(key, delta);
            return true;
        }

        /** The delta as the segment's next chunk, then the HEAD that counts it: the chunk before the count, so a crash
         *  leaves an orphan the merge never reads. A lost create is normally a redundant write of identical bytes, but
         *  the result is honoured - a resident chunk that differs is replaced, or the flush fails, since the count
         *  promises this chunk to the merge. */
        @Override
        protected byte[] flush(int segment, long generation, String cursor) throws IOException {
            flushing = chunkCount;
            if (!delta.isEmpty()) {
                String chunkKey = DependentsStore.chunkKey(segment, generation, chunkCount);
                byte[] chunk = DependentsStore.SegmentState.serializeChunk(delta);
                if (!store.writeVersioned(chunkKey, chunk, null)) {
                    Optional<ArtifactStore.Versioned> resident = store.readVersioned(chunkKey);
                    if (resident.isEmpty()) {
                        throw new IOException("dependents chunk " + chunkKey + " vanished after a lost create");
                    }
                    if (!Arrays.equals(resident.get().content(), chunk)
                            && !store.writeVersioned(chunkKey, chunk, resident.get().token())) {
                        throw takenOver();
                    }
                }
                flushing = chunkCount + 1;
            }
            return DependentsStore.SegmentState.serializeHead(generation, holder, flushing, cursor);
        }

        @Override
        protected void flushed() {
            chunkCount = flushing;
            delta.clear();
        }
    }
}
