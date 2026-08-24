package build.jenesis.repository.gc.store;

import build.jenesis.repository.format.BlobReferences;
import build.jenesis.repository.gc.GarbageCollector;
import build.jenesis.repository.gc.GcPlan;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilitySource;
import build.jenesis.repository.observation.TaskStatus;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.walk.ArtifactWalk;
import build.jenesis.repository.walk.WalkPass;

import module java.base;

/**
 * The reference {@link GarbageCollector}, riding the shared artifact walk - never its own listing loop - so both
 * of its enumerations are ordered, resumable, segmented and multi-node-safe, and no phase ever holds the whole
 * store in memory:
 *
 * <p><b>Mark, sharded.</b> One walk pass ({@code gc-mark}) over the caller's pointer roots reads each small leaf
 * object and keeps every hash it names, buffered in memory only up to the walk's checkpoint stride: the walk
 * flushes the buffer <em>before</em> every cursor commit ({@code KeyVisitor.beforeCheckpoint}), so a resume can
 * never skip a pointer whose reference was lost with a crashed buffer - the guard on the absolute invariant that
 * a referenced blob is never deleted. Flushed references land as immutable, append-only batch objects
 * {@code gc/<pass>/refs/<hh>/<collector>-<n>} sharded by the hash's leading byte (never a read-modify-write, so
 * concurrent workers and crash-replays only ever add duplicate observations, which union away). Once the mark
 * pass completes, its shards are complete for every live pointer: every segment was fully walked by whoever
 * finished it, so a straggler's late flush can only add redundancy.
 *
 * <p><b>Condemn-then-collect, across two consecutive passes.</b> A second walk pass ({@code gc-sweep}) streams the
 * flat {@code blobs/} namespace in hash order - so the current {@code <hh>} shard's references are the only set in
 * memory, O(N/256) - and judges each blob against the completed mark: a referenced blob has any stale
 * {@code gc/condemned/<hash>} marker removed; an unreferenced one is <em>condemned</em> (marker created, stamped
 * with this pass) the first time and <em>deleted only when its marker carries an earlier pass</em> - the marker is
 * the clock, giving every crash-torn or in-flight publish a full mark-pass enumeration of grace (spared the moment it
 * is referenced) with no store-timestamp API, and, when {@code jenreg.gc.grace} is set, a wall-clock floor on top so
 * a fast generation turnover across nodes cannot shorten it. The marker re-read immediately before deletion is the final guard: a dedup re-publish that
 * re-links condemned content clears the marker on the write path ({@code Publication.link}), collapsing the
 * residual race to the two back-to-back reads between that re-read and the delete. Blob first, marker last; a
 * marker whose blob is gone is swept by the convergence leg, which also drops the reference shards of superseded
 * passes.
 *
 * <p>Both phases only ever act on shapes they recognise: a leaf that names no SHA-256 is skipped at mark, and a
 * {@code blobs/} name that is not a hash is never judged, let alone deleted. A pointer body is read through
 * {@link ServableNames#hash(byte[])}, the one seam that owns the dialect a stored pointer body may carry - the bare
 * lower-case hex the {@code publish/} and {@code blobs/} pointers carry, or the algorithm-qualified
 * {@code sha256:<hex>} an OCI tag pointer carries - so both dialects name the same blob and both are counted. Only
 * the mark's <em>body</em> read normalises: every other name the collector judges ({@code gc/condemned/<hash>}
 * children, {@code blobs/} names, a raw hash) is a store key it writes itself and stays strictly bare hex.
 *
 * <p><b>What a pointer body cannot say, its format says.</b> One body naming one blob is exact only for a format whose
 * every served blob has a pointer. A format that serves blobs reachable only through a stored <em>document</em> has
 * more to declare, and declares it through {@link BlobReferences#references}: the mark asks the format that owns a
 * visited key what else that key keeps alive and unions the answer into the same reference set. The collector itself
 * still parses no format's documents - that neutrality is the reason this is a seam and not a special case - it only
 * unions what the owning format tells it with the hash it read itself. Handing it no lenders (the core's own
 * shape, and every existing test) leaves the mark byte-for-byte what it was.
 *
 * <p>An installed collector is its own {@link ObservabilitySource}: once it has run a {@link #collect} it reports
 * {@code jenreg.gc.condemned} - a gauge of the blobs currently condemned ({@code gc/condemned/}) awaiting the
 * confirming pass, the in-flight condemn-then-collect set the last sweep left standing - the
 * {@code jenreg.gc.collected} counter of blobs reclaimed (accumulated across collects), and a
 * {@code jenreg.gc.lastrun} task status stamped with the last collect. Because garbage collection is <em>no-op by
 * absence</em> (with no collector installed or selected there is no source at all), a deployment with GC off
 * contributes nothing here - GC-off is visible on the capabilities surface, not as a silent signal - and a
 * collector that has never run a pass likewise reports nothing until its first {@code collect}. The counts are read
 * off the collector's own last-pass bookkeeping, held on the instance (never a static) so they survive a pass
 * turnover; {@code plan} is a dry run and never touches them.
 */
public final class MarkSweepGarbageCollector implements GarbageCollector, ObservabilitySource {

    private static final AtomicReference<MarkSweepGarbageCollector> INSTALLED = new AtomicReference<>();

    /** Register {@code instance} as the live one the discovered {@link GarbageCollectorObservability} reports from; the production
     *  construction site calls this once, and the last registration wins. */
    public static void install(MarkSweepGarbageCollector instance) {
        INSTALLED.set(Objects.requireNonNull(instance, "instance"));
    }

    /** The installed live instance, if any - what {@link GarbageCollectorObservability} reports; empty before one is installed. */
    static Optional<MarkSweepGarbageCollector> installed() {
        return Optional.ofNullable(INSTALLED.get());
    }

    /** The two walk consumers, whose pass state a console reads back through {@code ArtifactWalk.pass}. */
    static final String MARK = "gc-mark", SWEEP = "gc-sweep";

    private static final String CONDEMNED = "gc/condemned";

    /** Names fetched per {@link ArtifactStore#page} call when streaming the marker space. */
    private static final int PAGE = 1000;

    /** A pointer names a hash in a few dozen bytes; a larger leaf is other metadata and is never read whole. */
    private static final int LARGEST_POINTER = 1024;

    private final ArtifactWalk walk;

    /** A wall-clock floor on the condemn-to-collect grace, on top of the one-pass generation gap. Zero (the default)
     *  keeps the grace purely generation-based: condemn in one pass, collect in the next. A positive value guards the
     *  case where generations advance faster than the nominal collection interval - several nodes each running
     *  {@code collect}, or a node restarting and re-collecting after a segment lease expires - by refusing to delete a
     *  blob until it has also carried its condemned marker for at least this long. Strictly more conservative than the
     *  generation gap alone: it can only ever delay a deletion, never bring one forward, so it cannot delete a blob
     *  the generation rule would spare. */
    private final Duration graceFloor;

    /** The installed formats that lend their reference sets, paired with the roots each declared - so a visited key is
     *  only ever offered to the format that owns its root, and a pointer-only deployment pays one prefix test per leaf.
     *  Empty for a deployment with no blobs-namespace format installed, which is exactly the pre-seam behaviour. */
    private final List<Lender> lenders;

    /** One format's reference-lending capability, narrowed to the root it declared it under. A format that declares
     *  several roots contributes one of these per root, so the ownership test stays a single {@code startsWith}. */
    private record Lender(String root, BlobReferences format) {

        private boolean owns(String key) {
            return key.length() > root.length() && key.startsWith(root) && key.charAt(root.length()) == '/';
        }
    }

    /** This collector's identity inside reference-batch names, so concurrent collectors never contend on a key. */
    private final String collector = UUID.randomUUID().toString().substring(0, 8);
    private final AtomicLong batches = new AtomicLong();

    /** Blobs reclaimed across every {@link #collect} this instance ran - the monotonic {@code jenreg.gc.collected}
     *  counter, held on the instance (never a static) so it survives a pass turnover. */
    private final AtomicLong collectedTotal = new AtomicLong();
    /** Blobs left condemned ({@code gc/condemned/}) awaiting the confirming pass, as the last completed-or-partial
     *  sweep counted them - the {@code jenreg.gc.condemned} in-flight gauge. */
    private volatile long condemnedStanding;
    /** When this instance last ran a {@code collect}, or {@code null} until it has - the gate that keeps a never-run
     *  (or GC-off, absent) collector reporting nothing. */
    private volatile Instant lastCollect;
    /** Whether that last collect completed both walk passes ({@code true}) or was partial because another node still
     *  held the shared enumeration ({@code false}). */
    private volatile boolean lastComplete;

    public MarkSweepGarbageCollector(ArtifactWalk walk) {
        this(walk, Duration.ZERO);
    }

    public MarkSweepGarbageCollector(ArtifactWalk walk, Duration graceFloor) {
        this(walk, graceFloor, List.of());
    }

    /** {@code lenders} are the installed {@link BlobReferences} formats ({@link BlobReferences#installed()}, resolved
     *  once by the provider so the collector itself carries no discovery), each contributing the blobs its documents
     *  keep alive beyond what a pointer body names. Handing an empty list is the pointer-body-only mark this collector
     *  has always run. A lender declaring a root the collector owns or judges ({@code blobs}, {@code gc},
     *  {@code walks}) is refused here rather than silently ignored: a format claiming to lend references under the
     *  namespace being swept is a wiring bug, and swallowing it would leave its blobs unmarked. */
    public MarkSweepGarbageCollector(ArtifactWalk walk, Duration graceFloor, List<BlobReferences> lenders) {
        this.walk = walk;
        this.graceFloor = graceFloor == null ? Duration.ZERO : graceFloor;
        List<Lender> owners = new ArrayList<>();
        for (BlobReferences lender : lenders == null ? List.<BlobReferences>of() : lenders) {
            for (String root : lender.blobRoots()) {
                owners.add(new Lender(root(root), lender));
            }
        }
        this.lenders = List.copyOf(owners);
    }

    @Override
    public GcPlan plan(ArtifactStore store, Known<List<String>> pointerRoots, Instant now) throws IOException {
        switch (pointerRoots) {
            case Known.Unknown<List<String>> unknown -> {
                return GcPlan.refused(unknown); // the dry run of a refusal is a refusal, not an empty plan
            }
            case Known.Present<List<String>> present -> roots(present.value());
            case Known.Absent<List<String>> _ -> throw new IllegalArgumentException(NO_ROOTS);
        }
        Optional<WalkPass> mark = walk.pass(store, MARK);
        long judged = mark.isEmpty() ? 0
                : mark.get().complete() ? mark.get().generation()
                : lastCompletedGeneration(store, mark.get().generation());
        if (judged <= 0) {
            return GcPlan.of(false, 0, 0, 0, List.of()); // no completed mark ever ran - nothing is due yet
        }
        References references = new References(store, judged);
        long[] due = {0};
        List<String> sample = new ArrayList<>();
        each(store, CONDEMNED, name -> {
            if (!hash(name) || !store.exists("blobs/" + name) || references.contains(name)) {
                return; // unrecognised, already-collected residue, or re-referenced
            }
            Marker parsed = store.readVersioned(CONDEMNED + "/" + name)
                    .map(MarkSweepGarbageCollector::parse).orElse(null);
            // Mirror collect()'s deletion test exactly, so the dry run previews precisely what the next collect would
            // reclaim: condemned by a judgment at or before the completed mark (an unreadable/newer marker is not due,
            // repaired by a sweep) AND past the wall-clock grace floor (zero by default). Applying the same floor here
            // is what keeps plan and collect in agreement - without it the dry run over-reports every blob still
            // inside its grace window.
            if (parsed == null || parsed.pass() > judged
                    || Duration.between(parsed.since(), now).compareTo(graceFloor) < 0) {
                return;
            }
            due[0]++;
            if (sample.size() < GcPlan.SAMPLE) {
                sample.add(name);
            }
        });
        return GcPlan.of(true, 0, 0, due[0], sample);
    }

    /** The generation of the most recent mark whose reference shards still stand - the largest {@code gc/<n>} below
     *  the current, in-progress generation. Preferred over {@code generation - 1} so the dry run stays correct after
     *  a corrupt-manifest recovery re-bases the generation on the wall clock (a jump, not a {@code +1}), which would
     *  otherwise point {@link References} at a {@code gc/<clock-1>} that never existed and preview every condemned
     *  blob as due. In the ordinary sequential case this <em>is</em> {@code generation - 1}. Zero when no earlier
     *  pass has left shards. */
    private static long lastCompletedGeneration(ArtifactStore store, long below) throws IOException {
        long best = 0;
        for (String child : store.list("gc")) {
            long pass;
            try {
                pass = Long.parseLong(child);
            } catch (NumberFormatException _) {
                continue; // the condemned space and anything unrecognised are not pass generations
            }
            if (pass < below && pass > best) {
                best = pass;
            }
        }
        return best;
    }

    @Override
    public GcPlan collect(ArtifactStore store, Known<List<String>> pointerRoots, Instant now) throws IOException {
        List<String> named;
        switch (pointerRoots) {
            case Known.Unknown<List<String>> unknown -> {
                // The root set could not be named in full, so some namespace's serving pointers are invisible to the
                // mark and every blob beneath them would read as unreferenced. Refuse before the mark begins:
                // nothing is walked, nothing is condemned, nothing is deleted, and the reason travels back with the
                // plan. The refusal is here - at the deletion - rather than left to every caller to remember.
                return GcPlan.refused(unknown);
            }
            case Known.Present<List<String>> present -> named = present.value();
            case Known.Absent<List<String>> _ -> throw new IllegalArgumentException(NO_ROOTS);
        }
        WalkPass marked = walk.walk(store, MARK, markRoots(named), new Mark(store));
        if (!marked.complete()) {
            // Another node still holds mark segments: the reference shards are not yet complete, and judging
            // blobs against an incomplete mark could condemn (though never delete) everything it missed. Report
            // the partial pass and let the next interval - or the node that finishes - do the judging.
            observe(now, false);
            return GcPlan.of(false, 0, 0, 0, List.of());
        }
        Sweep sweep = new Sweep(store, marked.generation(), now);
        WalkPass swept = walk.walk(store, SWEEP, List.of("blobs"), sweep);
        collectedTotal.addAndGet(sweep.collected);
        condemnedStanding = sweep.standing;
        if (!swept.complete()) {
            observe(now, false);
            return GcPlan.of(false, sweep.condemned, sweep.spared, sweep.collected, sweep.sample);
        }
        converge(store, marked.generation());
        observe(now, true);
        return GcPlan.of(true, sweep.condemned, sweep.spared, sweep.collected, sweep.sample);
    }

    /** Stamp the {@code jenreg.gc.*} observability state after a collect pass: the last-run instant the {@code
     *  jenreg.gc.lastrun} task status carries and whether it completed both walk passes. The condemned gauge and
     *  collected counter are updated at the sweep in {@link #collect} itself. */
    private void observe(Instant now, boolean complete) {
        lastComplete = complete;
        lastCollect = now;
    }

    @Override
    public List<Metric> metrics() {
        if (lastCollect == null) {
            return List.of(); // installed but never run - like a disabled plugin, lists nothing until first collect
        }
        return List.of(
                Metric.gauge("jenreg.gc.condemned",
                        "Blobs currently condemned (gc/condemned/) awaiting the confirming pass - the "
                                + "condemn-then-collect in-flight set the last sweep left standing.",
                        condemnedStanding, "blobs"),
                Metric.counter("jenreg.gc.collected",
                        "Blobs reclaimed by the mark-sweep collector, accumulated across collect passes - a "
                                + "monotonic count that climbs by what each collect deletes.",
                        collectedTotal.get(), "blobs"));
    }

    @Override
    public List<TaskStatus> taskStatuses() {
        Instant ran = lastCollect;
        if (ran == null) {
            // No collect has run - and with no collector installed or selected there is no source at all, so GC-off
            // is visible on the capabilities surface, never as a silent signal here.
            return List.of();
        }
        return List.of(TaskStatus.ran("jenreg.gc.lastrun",
                "The garbage-collection pass, stamped with its last collect - the mark-then-sweep that condemns "
                        + "unreferenced blobs and reclaims those an earlier pass already condemned.",
                TaskStatus.State.IDLE, ran, null,
                "reclaimed " + collectedTotal.get() + " blob(s), " + condemnedStanding + " condemned awaiting the "
                        + "next pass" + (lastComplete ? "" : " (partial: the shared walk still had segments held "
                        + "by another node)")));
    }

    /** The bookkeeping convergence after a completed sweep: a marker whose blob is gone (the residue of a crash
     *  between the blob and marker deletes, or of an already-collected blob) is removed, and the reference shards
     *  of every superseded pass are dropped - so the {@code gc/} space converges instead of growing forever, and
     *  an idempotent re-run over a converged store changes nothing. */
    private void converge(ArtifactStore store, long generation) throws IOException {
        each(store, CONDEMNED, name -> {
            if (hash(name) && !store.exists("blobs/" + name)) {
                deleteIfPresent(store, CONDEMNED + "/" + name);
            }
        });
        for (String child : store.list("gc")) {
            long pass;
            try {
                pass = Long.parseLong(child);
            } catch (NumberFormatException _) {
                continue; // the condemned space and anything unrecognised stay
            }
            if (pass < generation) {
                drop(store, "gc/" + child);
            }
        }
    }

    /** Delete a whole bookkeeping subtree (a superseded pass's reference batches - bounded, never artifacts). */
    private static void drop(ArtifactStore store, String prefix) throws IOException {
        if (store.exists(prefix)) {
            store.delete(prefix);
            return;
        }
        for (String child : store.list(prefix)) {
            drop(store, prefix + "/" + child);
        }
    }

    /** What an answered-but-empty root set means. {@code publish} always exists, so both {@link Known.Absent} ("the
     *  question was asked and there are no pointer roots") and a {@link Known.Present} empty list are contradictions
     *  rather than deployment states - and, unlike an unanswerable set, they are caller bugs, so they fail loudly
     *  (&sect;9) instead of being absorbed into a refusal an operator would have to go looking for. */
    private static final String NO_ROOTS = "garbage collection needs at least one pointer root, e.g. publish";

    /** Validate and normalise the caller's pointer roots: at least one, and never one of the store namespaces the
     *  collector itself owns or judges - marking {@code blobs} as a pointer root is a caller bug, not a layout. */
    private static List<String> roots(List<String> pointerRoots) {
        if (pointerRoots == null || pointerRoots.isEmpty()) {
            throw new IllegalArgumentException(NO_ROOTS);
        }
        return pointerRoots.stream().distinct().sorted().map(MarkSweepGarbageCollector::root).toList();
    }

    /** The roots the mark actually walks: the caller's, plus the roots every installed lender declared for itself.
     *  The caller still owns the layout - it names {@code publish} and whatever else it knows - but a lender that says
     *  "my blobs live under {@code oci/}" is the format's own word for it, and a deployment whose caller forgot that
     *  root would have the lender installed and never be asked, which is precisely how a blobs-namespace format's
     *  content becomes invisible to the scan and is reclaimed out from under it. Unioning can only ever enumerate more
     *  and therefore mark more, so it never deletes something the caller's list would have spared; each added root
     *  passes the same screen. In the ordinary case the caller already named them and this changes nothing. */
    private List<String> markRoots(List<String> pointerRoots) {
        Set<String> union = new TreeSet<>(roots(pointerRoots));
        for (Lender lender : lenders) {
            union.add(lender.root());
        }
        return List.copyOf(union);
    }

    /** One root, validated: never a namespace the collector itself owns or judges. */
    private static String root(String root) {
        if (root == null || root.isBlank() || root.equals("blobs") || root.equals("gc") || root.equals("walks")) {
            throw new IllegalArgumentException("not a pointer root: " + root);
        }
        return root;
    }

    /** The mark phase's visitor: buffer every hash a pointer leaf names, flushed as append-only batch objects
     *  before each walk checkpoint - so no committed cursor ever lies about an unflushed reference. */
    private final class Mark implements ArtifactWalk.KeyVisitor {

        private final ArtifactStore store;
        private final Map<String, List<String>> buffer = new HashMap<>(); // leading hash byte -> hashes to flush
        private long generation;

        private Mark(ArtifactStore store) {
            this.store = store;
        }

        @Override
        public void visit(String key) throws IOException {
            visit(ArtifactStore.Listed.of(key));
        }

        @Override
        public void visit(ArtifactStore.Listed entry) throws IOException {
            String key = entry.key();
            // The format-declared references FIRST, and deliberately outside the pointer-size gate below: that gate
            // bounds how large an object this phase will read as a POINTER BODY, and a format whose references live in
            // a document knows its own bound (BlobReferences clause 6). Asking after the gate would silently drop the
            // references of any key that is not itself pointer-shaped - the same class of omission fixed one line
            // down, and with the same consequence: an unmarked blob is condemned and then deleted.
            //
            // An IOException from a lender is NOT contained: BlobReferences clause 3 makes a short list illegal, so a
            // lender that cannot resolve a key it recognises throws, and the only safe reading of "I do not know what
            // this keeps alive" is to fail the pass. The walk propagates it, collect() never reaches the sweep, and
            // nothing is deleted. Catching it here would turn "cannot enumerate" into "references nothing", which is
            // exactly the fail-OPEN this seam exists to make unrepresentable.
            for (Lender lender : lenders) {
                if (!lender.owns(key)) {
                    continue;
                }
                for (String reference : lender.format().references(key, store)) {
                    // Judged by the same bare-hex predicate the body read is judged by, and sharded the same way, so a
                    // lent hash lands exactly where the sweep - which names a blob by its bare hex - looks for it.
                    String named = ServableNames.hash(reference);
                    if (hash(named)) {
                        buffer.computeIfAbsent(named.substring(0, 2), _ -> new ArrayList<>()).add(named);
                    }
                }
            }
            // The pointer-size gate, answered from the listing that enumerated this key wherever the backend's
            // listing carried a size - which is every shipped backend. A mark pass opens every key in the pointer
            // tree, so asking the store for each one's size was a round trip per pointer, spent only to decide not
            // to read the handful that are not pointer-shaped. A backend whose listing says nothing still gets
            // asked, so the gate is never weaker than it was.
            long size = entry.size().orElseGet(() -> {
                try {
                    return store.size(key);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            });
            if (size < 0 || size > LARGEST_POINTER) {
                return;
            }
            Optional<ArtifactStore.Versioned> pointer = store.readVersioned(key);
            if (pointer.isEmpty()) {
                return; // removed between the walk's listing and this read - nothing references through it
            }
            // The body's dialect is read through the one seam that owns it, never re-parsed here: a pointer body is
            // either the bare lower-case hex the free publish/ and blobs/ pointers carry or the algorithm-qualified
            // sha256:<hex> of the OCI Distribution tag pointers, and both denote the same blob. Reading it as bare hex
            // instead left every tag pointer unparsed, so the blob it references never entered the reference set and
            // the sweep condemned and then DELETED live content - the one thing this collector may never do. The
            // judgement below still applies to the bare hash, which is also how the sweep names a blob and how a
            // reference shard is keyed, so the reference is recorded where the sweep looks for it; a body in neither
            // dialect still names no hash and is still never counted. Same normalisation ServableNames.hash was
            // introduced for on the withhold screen, and the one RebuildPass reads its pointer bodies through.
            String named = ServableNames.hash(pointer.get().content());
            if (hash(named)) {
                buffer.computeIfAbsent(named.substring(0, 2), _ -> new ArrayList<>()).add(named);
            }
        }

        @Override
        public void beforeCheckpoint(String cursor) throws IOException {
            if (buffer.isEmpty()) {
                return;
            }
            if (generation == 0) {
                // The pass this worker is contributing to; read lazily since it exists only once the walk began.
                // Should the claim have been reclaimed and the manifest turned over, references land in the newer
                // pass's shards - a stale-but-true observation that can only ever spare a blob, never condemn one.
                generation = walk.pass(store, MARK).map(WalkPass::generation)
                        .orElseThrow(() -> new IOException("no mark pass to record references under"));
            }
            for (Map.Entry<String, List<String>> shard : buffer.entrySet()) {
                byte[] content = String.join("\n", shard.getValue()).getBytes(StandardCharsets.UTF_8);
                for (int attempt = 0; true; attempt++) {
                    String key = "gc/" + generation + "/refs/" + shard.getKey()
                            + "/" + collector + "-" + batches.incrementAndGet();
                    if (store.writeVersioned(key, content, null)) {
                        break; // create-if-absent under a collector-unique name: a collision is one in a billion
                    }
                    if (attempt == 2) {
                        throw new IOException("could not record a reference batch under " + key);
                    }
                }
            }
            buffer.clear();
        }
    }

    /** The sweep phase's visitor: judge each blob, in hash order, against the completed mark's shards. */
    private final class Sweep implements ArtifactWalk.KeyVisitor {

        private final ArtifactStore store;
        private final long generation;
        private final Instant now;
        private final References references;
        private long condemned, spared, collected;
        /** Blobs this sweep left carrying a condemned marker - newly condemned this pass plus those still within
         *  their grace - the in-flight {@code gc/condemned/} set the jenreg.gc.condemned gauge reports. */
        private long standing;
        private final List<String> sample = new ArrayList<>();

        private Sweep(ArtifactStore store, long generation, Instant now) {
            this.store = store;
            this.generation = generation;
            this.now = now;
            this.references = new References(store, generation);
        }

        @Override
        public void visit(String key) throws IOException {
            if (!key.startsWith("blobs/")) {
                return;
            }
            String hash = key.substring("blobs/".length());
            if (!hash(hash)) {
                return; // only content-addressed objects are ever judged, let alone deleted
            }
            String marker = CONDEMNED + "/" + hash;
            if (references.contains(hash)) {
                if (deleteIfPresent(store, marker)) {
                    spared++; // referenced again - the dedup re-publish an earlier pass condemned
                }
                return;
            }
            Optional<ArtifactStore.Versioned> current = store.readVersioned(marker);
            Marker parsed = current.map(MarkSweepGarbageCollector::parse).orElse(null);
            if (parsed == null) {
                // Unreferenced but not (recognisably) condemned yet: condemn it now, never delete it in the pass
                // that first judged it. Create-if-absent (an unreadable marker is repaired on its own token); a
                // lost race means a concurrent sweeper condemned it, which is convergence, not a lost update.
                var _ = store.writeVersioned(marker, marker(generation, now),
                        current.map(ArtifactStore.Versioned::token).orElse(null));
                condemned++;
                standing++; // now condemned, awaiting the confirming pass
            } else if (parsed.pass() < generation && Duration.between(parsed.since(), now).compareTo(graceFloor) >= 0
                    && referencesStillStand()) {
                // Condemned by an earlier pass, still unreferenced by this one, past the wall-clock grace floor
                // (zero by default), and our reference shards still stand (the lease fence below). Re-read the marker
                // as the final guard immediately before the destructive delete - after the lease-fence round-trip, not
                // the stale read from before it: a dedup re-publish that re-referenced these bytes since we judged them
                // clears the marker on its write path (the contract Publication.link documents), so a marker gone now
                // means the blob was re-linked and must be spared. The completed mark's shard needs no re-read: it
                // gained nothing but duplicates since the pass finished. Blob first, marker last, so a crash in between
                // leaves only a marker the convergence leg removes.
                if (store.readVersioned(marker).isEmpty()) {
                    spared++;   // a concurrent re-publish cleared the marker since we judged it - these bytes are referenced again
                    return;
                }
                deleteIfPresent(store, key);
                deleteIfPresent(store, marker);
                collected++;
                if (sample.size() < GcPlan.SAMPLE) {
                    sample.add(hash);
                }
            } else {
                // parsed.pass() >= generation, or younger than the grace floor: still within its grace, its marker
                // left standing for the confirming pass - part of the in-flight condemned set.
                standing++;
            }
        }

        /** A lease fence against deleting a blob after this sweep's reference shards were dropped from under it. The
         *  shards this sweep judges against live under {@code gc/<generation>/refs} (keyed by the mark generation),
         *  and {@link #converge} drops a pass's shards only for {@code pass < generation} - so they can only vanish
         *  once a mark completes at a generation strictly greater than ours. A paused or lease-expired sweep worker
         *  that resumes after that has stopped could otherwise re-judge against emptied shards and delete a still-
         *  referenced blob (every hash reads as unreferenced when the shards are gone). Re-reading the mark manifest
         *  immediately before each delete, and refusing when its generation has advanced past ours, closes that
         *  window: an advanced generation is the necessary precondition for our shards to have been dropped, so this
         *  never deletes against a superseded reference set. It is deliberately conservative - it may defer a still-
         *  safe delete while another node's newer mark is only in flight (its converge has not run) - which the next
         *  pass, marking afresh, re-judges and reclaims. Correctness over a marginal deletion this round. */
        private boolean referencesStillStand() throws IOException {
            // An unreadable mark manifest is not "no mark has advanced past me". walk.pass answers an empty Optional
            // both when no pass exists and when its manifest could not be read or parsed, and this is the fence
            // immediately in front of the delete - so an .orElse(0L) here would read a corrupt or unreachable
            // manifest as a lease that still stands and delete against reference shards that may already be gone.
            // Absence of the proof is not proof: with nothing to judge the lease by, the blob is spared and the next
            // pass re-judges it. We can only be here inside a sweep that followed a completed mark, so an empty
            // answer is always the unreadable case rather than a genuinely fresh store.
            return walk.pass(store, MARK).map(pass -> pass.generation() <= generation).orElse(false);
        }
    }

    /** The completed mark's reference shards, loaded one leading-byte shard at a time - both consumers stream
     *  hashes in name order, so this is a sequential read of at most 256 shards, never an O(N) set. */
    private static final class References {

        private final ArtifactStore store;
        private final long generation;
        private String shard;
        private Set<String> hashes = Set.of();

        private References(ArtifactStore store, long generation) {
            this.store = store;
            this.generation = generation;
        }

        private boolean contains(String hash) throws IOException {
            String leading = hash.substring(0, 2);
            if (!leading.equals(shard)) {
                shard = leading;
                hashes = load(leading);
            }
            return hashes.contains(hash);
        }

        private Set<String> load(String leading) throws IOException {
            Set<String> loaded = new HashSet<>();
            String prefix = "gc/" + generation + "/refs/" + leading;
            for (String batch : store.list(prefix)) {
                Optional<ArtifactStore.Versioned> content = store.readVersioned(prefix + "/" + batch);
                if (content.isPresent()) {
                    for (String line : new String(content.get().content(), StandardCharsets.UTF_8).split("\n")) {
                        if (!line.isBlank()) {
                            loaded.add(line.trim());
                        }
                    }
                }
            }
            return loaded;
        }
    }

    /** A condemned marker's content: the pass whose judgment condemned the blob (the clock the grace interval is
     *  measured in) and when - the {@code since} a console shows and the {@code jenreg.gc.grace} floor measures. */
    private record Marker(long pass, Instant since) {
    }

    private static byte[] marker(long pass, Instant since) {
        return ("pass=" + pass + "\nsince=" + since).getBytes(StandardCharsets.UTF_8);
    }

    /** Parse a marker; {@code null} for an unreadable one, which is re-stamped rather than trusted. */
    private static Marker parse(ArtifactStore.Versioned versioned) {
        try {
            Properties properties = new Properties();
            properties.load(new ByteArrayInputStream(versioned.content()));
            return new Marker(Long.parseLong(properties.getProperty("pass")),
                    Instant.parse(properties.getProperty("since")));
        } catch (IOException | RuntimeException _) {
            return null;
        }
    }

    private static boolean deleteIfPresent(ArtifactStore store, String key) throws IOException {
        if (!store.exists(key)) {
            return false;
        }
        store.delete(key);
        return true;
    }

    /** Whether a value is a bare SHA-256 - the only shape the collector ever trusts as naming a blob. Deliberately
     *  NOT widened to the qualified {@code sha256:<hex>} dialect: besides the mark's pointer body it also judges
     *  {@code gc/condemned/<hash>} child names, {@code blobs/} names and a raw hash, all of them store keys the
     *  collector writes itself and all of them bare hex, so widening here would make it accept a malformed name in
     *  three places to fix a dialect that occurs in one. The pointer body is normalised at its read instead, and this
     *  then judges the hash that body named. */
    private static boolean hash(String value) {
        if (value.length() != 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if ((character < '0' || character > '9') && (character < 'a' || character > 'f')) {
                return false;
            }
        }
        return true;
    }

    private interface NameAction {
        void accept(String name) throws IOException;
    }

    /** Stream every immediate child name under {@code prefix} through {@code action}, paged - never one list. */
    private static void each(ArtifactStore store, String prefix, NameAction action) throws IOException {
        String after = "";
        while (true) {
            List<String> names = new ArrayList<>();
            store.page(prefix, after, PAGE, names::add);
            for (String name : names) {
                action.accept(name);
            }
            if (names.size() < PAGE) {
                return;
            }
            after = names.getLast();
        }
    }
}
