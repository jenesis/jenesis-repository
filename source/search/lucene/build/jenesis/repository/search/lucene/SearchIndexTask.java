package build.jenesis.repository.search.lucene;

import module java.base;
import build.jenesis.repository.cleanup.Release;
import build.jenesis.repository.cleanup.RepositoryInventory;
import build.jenesis.repository.compliance.License;
import build.jenesis.repository.inventory.AboutSection;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.DirtyIndexFeed;
import build.jenesis.repository.walk.ArtifactWalk;
import build.jenesis.repository.walk.WalkPass;
import build.jenesis.repository.walk.WalkSegment;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import build.jenesis.repository.search.SearchMode;
import build.jenesis.repository.search.SearchQuery;
import build.jenesis.repository.store.Durations;

/**
 * The scheduled search-index pass, for each repository whose {@code full-text-search} setting is on; a repository with
 * it off costs this pass nothing, not a read. In steady state it is <b>O(&Delta;)</b> - it applies only what changed
 * since the last pass, read from the {@link DirtyIndexFeed} the {@link SearchPublicationObserver} marks on every
 * publish and delete - instead of re-deriving the whole coordinate set. Under the {@code search-index} lease (Lucene
 * wants a single writer) it opens the current generation, applies each touched coordinate with Lucene
 * {@code updateDocument} / {@code deleteDocuments} <em>by key term</em> (an idempotent upsert), writes the new
 * generation, cuts the {@code index/search/current} manifest over by compare-and-set, and only <em>then</em> advances
 * the cursor by {@link DirtyIndexFeed#clear clearing} the applied markers - so a crash between the commit and the clear
 * only replays already-applied markers, absorbed by the idempotent upsert. Search therefore lags a write by at most one
 * pass interval - explicit, and acceptable, and the one search leads a full-text answer with the name lookup's hits so
 * what was just published is found by name meanwhile.
 *
 * <p><b>An idle pass reads and never writes.</b> With nothing marked it reads the manifest and one page of the feed,
 * and returns: two small reads per repository per interval, no write. When the pass's own reconcile is due is a
 * question the manifest answers - it carries when the index was last rebuilt from truth - rather than a counter the
 * pass would have to bump on every idle interval.
 *
 * <p><b>The full rebuild stays for three cases</b>: <em>bootstrap</em> (no manifest yet), the
 * <em>format-version bump</em> (a stale-format manifest is discarded and rebuilt in the current format, never
 * migrated - the index is derived data), and a <em>reconcile</em> that rebuilds from durable truth to heal any drift
 * the events missed (an import or a manual store edit that bypassed the observer) and garbage-collects the feed
 * ({@link DirtyIndexFeed#compactThrough}). By default the walk reconciles, through {@link SearchRebuildConsumer};
 * {@value #RECONCILE} makes the pass itself reconcile once that long has passed since the last one, and the safety
 * valve {@code search-incremental=false} forces the full rebuild every pass. The full rebuild walks the inventory's
 * published rows on a pass of its own rather than riding the shared rebuild pass, because its unit is the version with
 * its coordinate: one row per version, where the pointer-level pass would hand it several pointers per version and no
 * coordinate.
 *
 * <p><b>What a document carries.</b> The coordinate and version, for the name a person types; and, from what the
 * publish recorded in the version's document - never by opening the artifact again - the description, keywords and
 * author names its manifest gave, for what a person remembers about a package when the name escapes them, and the
 * declared licences, for the licence inventory's counts and its drill-down. Reading them costs the one read of the
 * version's document the licences already took.
 *
 * <p><b>Correctness.</b> Each marker carries a monotonic {@code version} (the change's time); the applier skips an
 * entry whose version is older than the version already indexed for that coordinate (the out-of-order guard, so a
 * stale event never regresses a newer document) by leaving its marker in the feed rather than clearing it. The upsert
 * is by key term, so a replay is a no-op; the cursor (the cleared markers) advances only after the generation commits,
 * so a failed commit replays safely. Exclusive (a replicated deployment applies on one node per interval), so the
 * single-writer index and the manifest compare-and-set never lose a cutover.
 */
public final class SearchIndexTask implements MaintenanceTask {

    /** The task's pass-state scope under {@code walks/} - its own pass, never the retention sweep's. */
    public static final String CONSUMER = "search";

    /** How many superseded snapshots survive a cutover so an in-flight reader finishes streaming: the current
     *  generation plus the one just replaced. */
    private static final int KEEP_GENERATIONS = 2;

    /** The setting that makes the pass reconcile by itself once this long has passed since the last reconcile; unset,
     *  the default, leaves the reconcile to the walk's {@link SearchRebuildConsumer}. */
    static final String RECONCILE = "search-reconcile-interval";

    private final Duration interval;
    private final ArtifactWalk walk;

    public SearchIndexTask(Duration interval) {
        this(interval, null);
    }

    public SearchIndexTask(Duration interval, ArtifactWalk walk) {
        this.interval = interval;
        this.walk = walk;
    }

    @Override
    public String name() {
        return "search-index";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    /**
     * Exclusive <em>as well as</em> walk-claimed - deliberately both, and the justification is the index shape rather
     * than the enumeration. Lucene wants a single writer and the snapshot commits whole, so the pass applies on one
     * node per interval under the {@code search-index} lease; the walk (used only by the full rebuild's enumeration)
     * contributes the shared enumeration primitive, not multi-VM scale-out. Dropping the lease onto the segment claim
     * alone would not corrupt anything - {@link #accumulateOverWalk} already refuses to commit a rebuild it did not
     * enumerate {@linkplain #solo solo} - it would simply mean a fanned-out fleet never commits a full rebuild at all,
     * which is a rebuild that silently never happens. The accumulation is the {@code PASS_SNAPSHOT} shape the walk SPI
     * documents: it spans the whole pass in one worker's memory and may not span workers.
     */
    @Override
    public Exclusion exclusion() {
        return Exclusion.LEASE;
    }

    @Override
    public void repository(RepositoryContext context) throws IOException {
        if (SearchMode.of(context.config()) != SearchMode.FULL_TEXT) {
            return;   // off: nothing read, built or stored; an index left from when it was on is the walk's to remove
        }
        ArtifactStore store = context.store();
        SearchIndex index = new SearchIndex(store, SearchIndexTaskProvider.CLAIM.resolve(context.config()));
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        LicenseDerivation licenses = new LicenseDerivation(store);
        DirtyIndexFeed feed = new DirtyIndexFeed(store, SearchIndex.DIRECTORY);

        Optional<ArtifactStore.Versioned> stored = index.manifestVersioned();
        SearchManifest current = stored.map(versioned -> SearchManifest.parse(versioned.content())).orElse(null);
        Object token = stored.map(ArtifactStore.Versioned::token).orElse(null);

        boolean incrementalOn = !"false".equalsIgnoreCase(context.config().apply("search-incremental"));
        boolean usable = current != null && current.format() == SearchIndex.FORMAT;
        // The steady state: an incremental apply against the current generation, unless a full rebuild is due -
        // bootstrap (no usable manifest), the format-version bump (a stale-format manifest), the safety valve
        // (search-incremental=false), or the pass's own reconcile (its interval has passed since the last one).
        if (incrementalOn && usable && !reconcileDue(current, context.config())) {
            if (applyIncremental(store, index, inventory, licenses, feed, current, token, context)) {
                return;
            }
            // The incremental apply could not commit (the current generation was unreadable, or a concurrent
            // cutover won the manifest). Fall through to a full rebuild as the safe backstop rather than skipping.
        }
        Map<String, String> tags = Map.of("tenant", context.tenant(), "repository", context.repository());
        fullRebuild(store, index, inventory, licenses, feed, current, token,
                (name, description, value) -> context.gauge(name, description, tags, value));
    }

    /** A gauge the rebuild reports: the task routes it to the pass's registry, the walk consumer to the report. */
    @FunctionalInterface
    interface Gauges {
        void gauge(String name, String description, double value) throws IOException;
    }

    /** The full rebuild from truth over {@code store}, for the walk consumer that runs it when a walk carrying it
     *  completes: the same rebuild the task's periodic reconcile ran, with the feed compacted through the cutoff. */
    void rebuild(ArtifactStore store, Duration claim, Gauges gauges) throws IOException {
        SearchIndex index = new SearchIndex(store, claim);
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        LicenseDerivation licenses = new LicenseDerivation(store);
        DirtyIndexFeed feed = new DirtyIndexFeed(store, SearchIndex.DIRECTORY);
        Optional<ArtifactStore.Versioned> stored = index.manifestVersioned();
        SearchManifest current = stored.map(versioned -> SearchManifest.parse(versioned.content())).orElse(null);
        fullRebuild(store, index, inventory, licenses, feed, current,
                stored.map(ArtifactStore.Versioned::token).orElse(null), gauges);
    }

    // ---- incremental (O(Δ)) steady state -------------------------------------------------------------------------

    /**
     * Apply the dirty set against the current snapshot and cut a new generation over - the O(&Delta;) steady state.
     * Reads the touched coordinates ({@link DirtyIndexFeed#pending}), upserts/deletes each by coordinate term
     * against the current snapshot opened in memory, writes the new snapshot, cuts the manifest over, then advances
     * the cursor ({@link DirtyIndexFeed#clear}) only after the commit. Returns {@code true} when the sweep handled
     * this pass (committed a new generation, or found nothing to do), {@code false} when it could not commit and the
     * caller should fall back to a full rebuild. An empty feed is a no-op success: nothing changed since last sweep.
     */
    private boolean applyIncremental(ArtifactStore store, SearchIndex index, StoreRepositoryInventory inventory,
                                     LicenseDerivation licenses, DirtyIndexFeed feed, SearchManifest current,
                                     Object token, RepositoryContext context) throws IOException {
        if (feed.pending(1).isEmpty()) {
            return true;                  // nothing changed - keep the current generation untouched, write nothing
        }
        Directory directory;
        try {
            directory = index.next(current.generation());   // the next generation's own directory, appended to
        } catch (IOException | RuntimeException unreadable) {
            return false;                 // the current snapshot is gone/corrupt - let the caller full-rebuild
        }
        int generation = current.generation() + 1;
        List<DirtyIndexFeed.Entry> applied = new ArrayList<>();
        long documents;
        try {
            // A point-in-time reader over the pre-apply snapshot for the out-of-order guard, opened before the writer
            // mutates the directory, so a guard read sees the version already indexed for a coordinate.
            try (DirectoryReader before = DirectoryReader.open(directory)) {
                IndexSearcher guard = new IndexSearcher(before);
                IndexWriter writer = new IndexWriter(directory,
                        new IndexWriterConfig(CoordinateAnalyzer.fields()).setOpenMode(IndexWriterConfig.OpenMode.APPEND));
                try {
                    // Paged through the feed's own drain, applied into one writer, cleared only after the snapshot
                    // below has committed - so nothing here clears as it goes.
                    feed.drain(ArtifactStore.DRAIN_PAGE, page -> {
                        for (DirtyIndexFeed.Entry entry : page) {
                            if (apply(entry, writer, guard, inventory, licenses, store)) {
                                applied.add(entry);
                            }
                        }
                    });
                } finally {
                    writer.close();
                }
            }
            if (applied.isEmpty()) {
                return true;              // every pending marker was skipped by the guard - keep the current generation
            }
            byte[] facets;
            try (DirectoryReader after = DirectoryReader.open(directory)) {
                documents = after.numDocs();
                facets = LicenseFacets.serialize(LicenseFacets.fromIndex(new IndexSearcher(after)));
            }
            Optional<String> checksum = index.writeSnapshot(generation, directory);
            if (checksum.isEmpty()) {
                return false;   // a concurrent rebuild claimed this generation and cuts over next; ours wrote only segments
            }
            index.writeFacets(generation, facets);
            SearchManifest manifest = new SearchManifest(generation, SearchIndex.FORMAT, documents, checksum.get(),
                    current.reconciled());
            if (!index.putManifest(manifest, token)) {
                // A concurrent cutover won (rare under the exclusive lease). Drop our orphan generation and let the
                // caller full-rebuild; the feed is NOT cleared, so nothing is lost.
                SearchManifest winner = index.manifestVersioned()
                        .map(versioned -> SearchManifest.parse(versioned.content())).orElse(null);
                if (winner == null || winner.generation() != generation) {
                    index.deleteSnapshot(generation);
                    index.deleteFacets(generation);
                }
                return false;
            }
        } finally {
            directory.close();
        }
        // Crash-safe advance: the snapshot has committed, so now (and only now) drop the applied markers - a coordinate
        // re-touched during the sweep survives (clear is token-fenced), a crash before here replays them next sweep.
        feed.clear(applied);
        gcSuperseded(index, generation);
        gauges(context, index, generation, documents);
        return true;
    }

    /** Apply one dirty marker to the writer, returning whether it was applied (so the caller clears it) or skipped by
     *  the out-of-order guard (its version is older than the version already indexed for the coordinate, so it is left
     *  in the feed). A {@code removed} marker deletes the coordinate's document; a touched marker re-derives the
     *  coordinate's document from durable truth (reading only that one coordinate - the O(&Delta;) guarantee) and
     *  upserts it by term, or deletes it when truth no longer carries the coordinate. */
    private static boolean apply(DirtyIndexFeed.Entry entry, IndexWriter writer, IndexSearcher guard,
                                 StoreRepositoryInventory inventory, LicenseDerivation licenses, ArtifactStore store)
            throws IOException {
        String path = parsePathKey(entry.coordinate());
        String[] parts = path == null ? parseKey(entry.coordinate()) : null;
        if (path == null && parts == null) {
            return true;                  // an unmappable marker (neither kind of key) is dropped rather than kept
        }
        Term keyTerm = new Term("key", entry.coordinate());
        Long indexed = indexedVersion(guard, keyTerm);
        if (indexed != null && entry.version() < indexed) {
            return false;                 // out-of-order guard: a stale event never regresses a newer document
        }
        if (entry.removed()) {
            writer.deleteDocuments(keyTerm);
            return true;
        }
        if (path != null) {
            // Truth for a path-addressed artifact is the serving pointer: one point read, so this stays O(1) per
            // marker exactly as the coordinate leg does. Gone means the artifact was unpublished between the mark
            // and this sweep, which is a delete rather than a stale document left behind.
            if (store == null || new Publication(store).locate(path).isEmpty()) {
                writer.deleteDocuments(keyTerm);
                return true;
            }
            writer.updateDocument(keyTerm, document(entry.coordinate(), path, entry.version()));
            return true;
        }
        Release release = release(inventory, parts[0], parts[1], parts[2]);
        if (release == null) {
            writer.deleteDocuments(keyTerm);   // touched, but truth no longer carries it - treat as a delete
            return true;
        }
        writer.updateDocument(keyTerm, document(entry.coordinate(), release, inventory, licenses, entry.version()));
        return true;
    }

    /** The version already indexed for a coordinate key, or {@code null} when it is not in the index - the out-of-order
     *  guard's read against the pre-apply reader. */
    private static Long indexedVersion(IndexSearcher guard, Term keyTerm) throws IOException {
        TopDocs top = guard.search(new TermQuery(keyTerm), 1);
        if (top.scoreDocs.length == 0) {
            return null;
        }
        var field = guard.storedFields().document(top.scoreDocs[0].doc).getField("version");
        return field == null || field.numericValue() == null ? null : field.numericValue().longValue();
    }

    /** Re-derive one coordinate version's {@link Release} from durable truth by the inventory's point read - the
     *  one document that version's facts live in - which is what keeps the incremental apply O(&Delta;). Listing
     *  every version of the coordinate to find the one the marker named, reading a document per version, would cost a
     *  hundred thousand reads for a marker on a coordinate of a hundred thousand versions, and every later change to an
     *  often-released coordinate would cost every earlier one. */
    private static Release release(StoreRepositoryInventory inventory, String ecosystem, String coordinate,
                                  String version) throws IOException {
        return inventory.release(ecosystem, coordinate, version).orElse(null);
    }

    // ---- full rebuild (bootstrap / format bump / safety-valve / periodic reconcile) ------------------------------

    private void fullRebuild(ArtifactStore store, SearchIndex index, StoreRepositoryInventory inventory,
                             LicenseDerivation licenses, DirtyIndexFeed feed, SearchManifest current, Object token,
                             Gauges gauges) throws IOException {
        int generation = (current == null ? 0 : current.generation()) + 1;
        // The reconcile cutoff, captured BEFORE the enumeration reads truth: a change marked after the rebuild started
        // (and possibly not reflected in it) has a newer version and is kept for the next incremental sweep; everything
        // recorded no later than this - reflected by the rebuild from truth - is GC'd from the feed after the commit.
        long cutoff = System.currentTimeMillis();

        Accumulation accumulation = walk == null ? accumulateStreaming(inventory, licenses)
                : accumulateOverWalk(store, inventory, licenses);
        if (accumulation == null) {
            return; // another worker still owns walk segments; nothing this run could commit is whole - defer
        }

        try {
            Optional<String> checksum = index.writeSnapshot(generation, accumulation.directory);
            if (checksum.isEmpty()) {
                return;         // a concurrent rebuild claimed this generation and cuts over next; ours wrote only segments
            }
            index.writeFacets(generation, LicenseFacets.serialize(
                    LicenseFacets.toFacets(accumulation.categories, accumulation.spdx)));
            SearchManifest manifest = new SearchManifest(generation, SearchIndex.FORMAT, accumulation.documents,
                    checksum.get(), Instant.ofEpochMilli(cutoff));
            if (!index.putManifest(manifest, token)) {
                SearchManifest winner = index.manifestVersioned()
                        .map(versioned -> SearchManifest.parse(versioned.content())).orElse(null);
                if (winner == null || winner.generation() != generation) {
                    index.deleteSnapshot(generation);
                    index.deleteFacets(generation);
                }
                return;
            }
            gcSuperseded(index, generation);
            // The rebuild reflects durable truth, so the feed's applied-through markers are now redundant: GC them.
            // A marker newer than the cutoff (a publish that raced the rebuild) is kept.
            feed.compactThrough(cutoff);
            gauges.gauge("jenrepo.search.documents", "Documents in the search index", accumulation.documents);
            gauges.gauge("jenrepo.search.bytes", "Compressed size of the search index snapshot",
                    index.snapshotSize(generation));
        } finally {
            accumulation.release();   // the scratch directory: its files are linked into the cache, or the build is abandoned
        }
    }

    /** The walk-less build: one complete-per-call streaming tree walk of the version documents - every
     *  release folds into the writer as the stream flows, never a buffered release list. */
    private static Accumulation accumulateStreaming(StoreRepositoryInventory inventory, LicenseDerivation licenses)
            throws IOException {
        Accumulation accumulation = new Accumulation(inventory, licenses);
        try (accumulation) {
            inventory.releases(accumulation);
            inventory.servedPaths(path -> accumulation.visitServedPath(inventory, path));
        }
        return accumulation;
    }

    /**
     * The walk-riding build: stream the releases over this task's {@code walks/search} pass and return the
     * accumulation only when this run provably saw the whole repository - the restart-on-crash discipline.
     */
    private Accumulation accumulateOverWalk(ArtifactStore store, StoreRepositoryInventory inventory,
                                            LicenseDerivation licenses) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            boolean fresh = walk.pass(store, CONSUMER).map(WalkPass::complete).orElse(true);
            Accumulation accumulation = new Accumulation(inventory, licenses);
            WalkPass pass;
            try (accumulation) {
                pass = inventory.releases(walk, CONSUMER, accumulation,
                        path -> accumulation.visitServedPath(inventory, path));
            }
            if (!pass.complete()) {
                accumulation.release();
                return null;
            }
            if (fresh && solo(walk.segments(store, CONSUMER), pass.generation())) {
                return accumulation;
            }
            accumulation.release();   // not provably whole: the next attempt accumulates afresh
        }
        return null;
    }

    /** Whether every segment of {@code generation} is done and a single worker finished them all - proof the caller's
     *  one walk enumerated the entire pass. */
    /** The pass's gauges on its registry: what the walk consumer reports to the observability report instead. */
    private static void gauges(RepositoryContext context, SearchIndex index, int generation, long documents)
            throws IOException {
        Map<String, String> tags = Map.of("tenant", context.tenant(), "repository", context.repository());
        context.gauge("jenrepo.search.documents", "Documents in the search index", tags, documents);
        context.gauge("jenrepo.search.bytes", "Compressed size of the search index snapshot", tags,
                index.snapshotSize(generation));
    }

    private static boolean solo(List<WalkSegment> segments, long generation) {
        Set<String> holders = new HashSet<>();
        for (WalkSegment segment : segments) {
            if (segment.generation() != generation || segment.state() != WalkSegment.State.DONE) {
                return false;
            }
            holders.add(segment.holder());
        }
        return holders.size() == 1;
    }

    // ---- shared -------------------------------------------------------------------------------------------------

    private static void gcSuperseded(SearchIndex index, int generation) throws IOException {
        for (int superseded : index.generations()) {
            if (superseded != generation && superseded <= generation - KEEP_GENERATIONS) {
                index.deleteSnapshot(superseded);
                index.deleteFacets(superseded);
            }
        }
        index.gcSegments();   // a segment file no remaining generation names
    }


    /** Whether the pass's own reconcile is due: {@value #RECONCILE} is set and that long has passed since the manifest
     *  says the index was last rebuilt from truth. Unset or unreadable, never - the walk reconciles. */
    private static boolean reconcileDue(SearchManifest current, UnaryOperator<String> config) {
        String value = config.apply(RECONCILE);
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            Duration every = Durations.parse(value);
            return every.isPositive() && !Instant.now().isBefore(current.reconciled().plus(every));
        } catch (IllegalArgumentException unreadable) {
            return false;
        }
    }

    /** The Lucene coordinate term a release document is keyed by (the upsert/delete term) - the neutral
     *  {@code ecosystem/coordinate/version} identity joined with a separator no coordinate carries, so it round-trips
     *  and never splits. Shared with {@link SearchPublicationObserver}, which marks the feed with the same key. */
    public static String coordinateKey(String ecosystem, String coordinate, String version) {
        return (ecosystem == null ? "" : ecosystem) + SEPARATOR + coordinate + SEPARATOR + version;
    }

    /** The search hit an indexed document is: a path-addressed artifact by its served path, a coordinate version by
     *  the parts of its key - read back from the key rather than split out of the display, which a version containing
     *  a colon would mis-split. */
    static SearchQuery.Hit hit(String key, String display) {
        String path = key == null ? null : parsePathKey(key);
        if (path != null) {
            return SearchQuery.Hit.path(path);
        }
        String[] parts = key == null ? null : parseKey(key);
        if (parts == null) {
            return SearchQuery.Hit.path(display);
        }
        return SearchQuery.Hit.coordinate(parts[0], parts[1], parts[2]);
    }

    /** Split a coordinate key back into its {@code {ecosystem, coordinate, version}} parts, or {@code null} when it is
     *  not a full three-part key (a path key, or an unmappable marker). */
    static String[] parseKey(String key) {
        String[] parts = key.split(SEPARATOR, -1);
        return parts.length == 3 ? parts : null;
    }

    /**
     * The index key of a PATH-ADDRESSED artifact - one served by request path with no coordinate to be indexed
     * under, which is what a raw upload is. Two parts where a coordinate key has three, so the two key spaces
     * cannot collide and {@link #parseKey} goes on rejecting this as "not a coordinate".
     *
     * <p>They are in the index at all because search would otherwise have to find them by walking the store on
     * every request: the coordinate index answers about packages, and a path-addressed artifact has no coordinate
     * to be in it, so {@code /api/search} unioned a live tree walk into its answer - up to twenty thousand names
     * examined, each costing up to four store reads under the withheld screen. That is nothing over a directory
     * and minutes over an object store, which is a request-time cost growing with what the repository holds.
     */
    static String pathKey(String requestPath) {
        return PATH_MARKER + SEPARATOR + requestPath;
    }

    /** The request path a {@link #pathKey} names, or {@code null} when the key is not one. */
    static String parsePathKey(String key) {
        String[] parts = key.split(SEPARATOR, -1);
        return parts.length == 2 && PATH_MARKER.equals(parts[0]) ? parts[1] : null;
    }

    /** The first part of a path key. A request path always begins with {@code /}, so it can never be mistaken for an
     *  ecosystem and this marker can never be mistaken for one either. */
    private static final String PATH_MARKER = "path";

    /** The unit-separator (U+001F) joining a coordinate key's three parts - a control character no
     *  ecosystem, coordinate or version carries, so the key round-trips and never splits wrongly. */
    private static final String SEPARATOR = "\u001f";

    /** One build's whole state during a full rebuild: the file-backed scratch directory its writer fills release
     *  by release - never a heap-held index, which a million documents could not fit in a server that sets no
     *  heap - and the license facet tallies resolved along the way. */
    private static final class Accumulation implements RepositoryInventory.ReleaseVisitor, Closeable {

        private final Directory directory = SearchIndex.scratch();
        private final IndexWriter writer;
        private final Map<String, Long> categories = new TreeMap<>();
        private final Map<String, Long> spdx = new TreeMap<>();
        private final StoreRepositoryInventory inventory;
        private final LicenseDerivation licenses;
        private long documents;

        private Accumulation(StoreRepositoryInventory inventory, LicenseDerivation licenses) throws IOException {
            this.inventory = inventory;
            this.licenses = licenses;
            writer = new IndexWriter(directory, new IndexWriterConfig(CoordinateAnalyzer.fields()));
        }

        @Override
        public void visit(Release release) throws IOException {
            String key = coordinateKey(release.ecosystem(), release.coordinate(), release.version());
            long version = release.published() == null ? 0 : release.published().toEpochMilli();
            Document document = document(key, release, inventory, licenses, version);
            writer.addDocument(document);
            tallyFacets(document, categories, spdx);
            documents++;
        }

        /**
         * One served request path, indexed when - and only when - no coordinate describes it.
         *
         * <p>That filter is the whole difference between indexing a document per stored FILE and one per
         * coordinate-less artifact. A `.pom` or a `.jar` of a published package is already in this index under its
         * coordinate, so indexing its filename too would multiply the index several-fold to say a second time what
         * the coordinate document already says. What has no coordinate - a raw upload - is in the index only if it
         * is put there by this, rather than found by walking the store on every search request.
         *
         * <p>The question is put to {@code pathAddressed}, which asks the FORMAT, and not to {@code describe}: that
         * face is empty both for a path no format claims and for one a layout claims but cannot parse, so filtering
         * on it would index a second document for every artifact whose path a layout could not read.
         *
         * <p>One point read per pointer, in a background pass whose cost is proportional to what the repository
         * holds by design. That is the trade the bounded-read rule asks for: pay it here, once a week, rather than
         * on every request a client makes.
         */
        void visitServedPath(StoreRepositoryInventory inventory, String requestPath) throws IOException {
            if (!inventory.pathAddressed(requestPath)) {
                return;
            }
            writer.addDocument(document(pathKey(requestPath), requestPath, 0L));
            documents++;
        }

        @Override
        public void close() throws IOException {
            writer.close();
        }

        /** Release the scratch directory: after {@link SearchIndex#writeSnapshot} has linked its files into the
         *  node's cache, or when the build is abandoned - either way nothing under it is needed again. */
        void release() throws IOException {
            SearchIndex.discard(directory);
        }
    }

    /** Merge one document's licences into the running facet tallies: its SPDX ids and its categories, each once. */
    private static void tallyFacets(Document document, Map<String, Long> categories, Map<String, Long> spdx) {
        new LinkedHashSet<>(Arrays.asList(document.getValues("license_id")))
                .forEach(id -> spdx.merge(id, 1L, Long::sum));
        new LinkedHashSet<>(Arrays.asList(document.getValues("category")))
                .forEach(category -> categories.merge(category, 1L, Long::sum));
    }

    /**
     * One indexed PATH-ADDRESSED document: its display is the served request path itself, which is what search has
     * always returned for these and what a caller uses to fetch one.
     *
     * <p>The leading {@code /} is the discriminator a caller screens by, and it is a property of the two display
     * shapes rather than a convention invented here: a request path always begins with a slash and a
     * {@code coordinate:version} display never does. It matters because the two are screened differently -
     * {@code disclosableDisplay} is the {@code coordinate:version} face and refuses a bare name outright, saying a
     * name-level surface must go through {@code ServableNames} instead.
     *
     * <p>No licence facets: a path-addressed artifact has no coordinate, so nothing declares a licence for it, and
     * a {@code license:}/{@code category:} filter is a question about packages that this document cannot answer.
     * It therefore drops out of a filtered query rather than matching it emptily.
     */
    private static Document document(String key, String requestPath, long version) {
        Document document = new Document();
        document.add(new StringField("key", key, Field.Store.YES));
        document.add(new StoredField("version", version));
        document.add(new StringField("display", requestPath, Field.Store.YES));
        document.add(new SortedDocValuesField(LuceneSearcher.SORT_FIELD, new BytesRef(requestPath)));
        document.add(new TextField(LuceneSearcher.NAME_FIELD, requestPath, Field.Store.NO));
        document.add(new TextField(LuceneSearcher.TEXT_FIELD, requestPath, Field.Store.NO));
        return document;
    }

    /**
     * One indexed coordinate version: the {@code key} term (the exact upsert/delete key), the numeric {@code version}
     * the out-of-order guard reads back, the {@code coordinate:version} display as both a stored term and its sorted
     * doc-values twin (so a query pages in display order through the index), the ecosystem and coordinate as the
     * name a person types, the text a person remembers - the version, and what the version's manifest said about the
     * package: its description, its keywords and the names of the people it credits - and the declared licences as
     * queryable facets.
     *
     * <p>What the manifest said and the licences come from one read of the version's document, where the publish
     * recorded them; nothing here opens the artifact. A version published before that was recorded has no description
     * to index, and is found by its name until it is published again.
     */
    private static Document document(String key, Release release, StoreRepositoryInventory inventory,
                                     LicenseDerivation derivation, long version) throws IOException {
        Optional<StoreRepositoryInventory.Searchable> searchable =
                inventory.searchable(release.ecosystem(), release.coordinate(), release.version());
        List<License> licenses = derivation.resolve(release,
                searchable.flatMap(StoreRepositoryInventory.Searchable::licenses));
        Optional<AboutSection.About> about = searchable.flatMap(StoreRepositoryInventory.Searchable::about);

        Document document = new Document();
        document.add(new StringField("key", key, Field.Store.YES));
        document.add(new StoredField("version", version));
        String display = release.coordinate() + ":" + release.version();
        document.add(new StringField("display", display, Field.Store.YES));
        document.add(new SortedDocValuesField(LuceneSearcher.SORT_FIELD, new BytesRef(display)));
        StringBuilder name = new StringBuilder();
        if (release.ecosystem() != null) {
            name.append(release.ecosystem()).append(' ');
        }
        name.append(release.coordinate());
        document.add(new TextField(LuceneSearcher.NAME_FIELD, name.toString(), Field.Store.NO));
        StringBuilder text = new StringBuilder(name).append(' ').append(release.version());
        about.ifPresent(said -> {
            if (said.description() != null) {
                text.append(' ').append(said.description());
            }
            said.keywords().forEach(keyword -> text.append(' ').append(keyword));
            said.authors().forEach(author -> text.append(' ').append(author));
        });
        document.add(new TextField(LuceneSearcher.TEXT_FIELD, text.toString(), Field.Store.NO));

        Set<String> spdx = new LinkedHashSet<>();
        Set<String> categories = new LinkedHashSet<>();
        for (License license : licenses) {
            if (license.identified()) {
                spdx.add(license.spdxId());
            }
            categories.add(license.category());
        }
        for (String id : spdx) {
            document.add(new StringField("license", id.toLowerCase(Locale.ROOT), Field.Store.NO));   // filter term
            document.add(new StoredField("license_id", id));                                          // facet display
        }
        for (String category : categories) {
            document.add(new StringField("category", category, Field.Store.YES));   // category values are lower-case
        }
        return document;
    }
}
