package build.jenesis.repository.search.lucene;

import module java.base;
import build.jenesis.repository.cleanup.Release;
import build.jenesis.repository.cleanup.RepositoryInventory;
import build.jenesis.repository.compliance.License;
import build.jenesis.repository.compliance.LicenseTable;
import build.jenesis.repository.compliance.inventory.LicenseDerivation;
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
import org.apache.lucene.analysis.Analyzer;
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
 * The scheduled search-index pass, for each repository whose {@code full-text-search} setting is on; one with it off
 * costs nothing. In steady state it is <b>O(&Delta;)</b>: it applies only what the {@link SearchPublicationObserver}
 * marked in the {@link DirtyIndexFeed} since the last pass. Under the {@code search-index} lease (Lucene wants a single
 * writer) it opens the current generation, upserts or deletes each touched coordinate by key term, writes the new
 * generation, cuts {@code index/search/current} over by compare-and-set, and only then
 * {@link DirtyIndexFeed#clear clears} the applied markers, so a crash between replays markers the idempotent upsert
 * absorbs. Search lags a write by at most one interval, while the name lookup finds what was just published.
 *
 * <p><b>An idle pass reads and never writes:</b> the manifest and one page of the feed. Whether its own reconcile is
 * due is answered by the manifest, which records when the index was last rebuilt from truth.
 *
 * <p><b>A full rebuild</b> runs on <em>bootstrap</em> (no manifest), on a <em>format bump</em> (a stale-format index is
 * rebuilt, never migrated), and on <em>reconcile</em>, which heals drift the events missed and compacts the feed
 * ({@link DirtyIndexFeed#compactThrough}). The walk reconciles by default ({@link SearchRebuildConsumer});
 * {@value #RECONCILE} makes the pass reconcile by itself, and {@code search-incremental=false} rebuilds every pass. The
 * rebuild walks the inventory's published rows, one per version with its coordinate, rather than the pointer-level
 * rebuild pass.
 *
 * <p><b>What a document carries:</b> the coordinate and version; the description, keywords and authors the publish
 * recorded in the version's document; and the declared licences for the {@code license:} and {@code category:} filters,
 * all from the one read of the version's document.
 *
 * <p><b>Correctness.</b> Each marker carries a monotonic version, and one older than the version already indexed is
 * skipped and left in the feed, so a stale event never regresses a document. The upsert is by key term, so a replay is
 * a no-op, and markers clear only after the generation commits. Exclusive, so the single writer and the manifest
 * compare-and-set never lose a cutover.
 */
public final class SearchIndexTask implements MaintenanceTask {

    /** The task's pass-state scope under {@code walks/}. */
    public static final String CONSUMER = "search";

    /** How many generations survive a cutover, so an in-flight reader finishes: the current and the one replaced. */
    private static final int KEEP_GENERATIONS = 2;

    /** The setting that makes the pass reconcile by itself once this long has passed since the last reconcile; unset,
     *  the walk's {@link SearchRebuildConsumer} reconciles. */
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

    /** Exclusive as well as walk-claimed: Lucene wants a single writer and the snapshot commits whole, so the pass
     *  applies on one node under the {@code search-index} lease, while the walk lends the full rebuild its enumeration.
     *  Without the lease a fanned-out fleet would never commit a rebuild, since {@link #accumulateOverWalk} commits
     *  only one it enumerated {@linkplain #solo solo}. The accumulation spans the whole pass in one worker
     *  ({@code PASS_SNAPSHOT}). */
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
        LicenseDerivation licenses = new LicenseDerivation(store, LicenseTable.of(context.config()));
        DirtyIndexFeed feed = new DirtyIndexFeed(store, SearchIndex.DIRECTORY);

        Optional<ArtifactStore.Versioned> stored = index.manifestVersioned();
        SearchManifest current = stored.map(versioned -> SearchManifest.parse(versioned.content())).orElse(null);
        Object token = stored.map(ArtifactStore.Versioned::token).orElse(null);

        boolean incrementalOn = !"false".equalsIgnoreCase(context.config().apply("search-incremental"));
        boolean usable = current != null && current.format() == SearchIndex.FORMAT;
        // The incremental apply, unless a full rebuild is due: bootstrap, a format bump, the safety valve, or the
        // pass's own reconcile.
        if (incrementalOn && usable && !reconcileDue(current, context.config())) {
            if (applyIncremental(store, index, inventory, licenses, feed, current, token, context)) {
                return;
            }
            // The apply could not commit (an unreadable generation, or a concurrent cutover won), so a full rebuild is
            // the backstop.
        }
        Map<String, String> tags = Map.of("tenant", context.tenant(), "repository", context.repository());
        fullRebuild(store, index, inventory, licenses, feed, current, token,
                (name, description, value) -> context.gauge(name, description, tags, value));
    }

    /** A gauge the rebuild reports: to the pass's registry from the task, to the report from the walk consumer. */
    @FunctionalInterface
    interface Gauges {
        void gauge(String name, String description, double value) throws IOException;
    }

    /** The full rebuild from truth over {@code store}, for the walk consumer: the feed compacted through the cutoff,
     *  and licences identified through {@code table}, built from the repository's settings. */
    void rebuild(ArtifactStore store, Duration claim, LicenseTable table, Gauges gauges) throws IOException {
        SearchIndex index = new SearchIndex(store, claim);
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        LicenseDerivation licenses = new LicenseDerivation(store, table);
        DirtyIndexFeed feed = new DirtyIndexFeed(store, SearchIndex.DIRECTORY);
        Optional<ArtifactStore.Versioned> stored = index.manifestVersioned();
        SearchManifest current = stored.map(versioned -> SearchManifest.parse(versioned.content())).orElse(null);
        fullRebuild(store, index, inventory, licenses, feed, current,
                stored.map(ArtifactStore.Versioned::token).orElse(null), gauges);
    }

    /** Apply the dirty set against the current snapshot and cut a new generation over: read the touched coordinates
     *  ({@link DirtyIndexFeed#pending}), upsert or delete each by key term, write the snapshot, cut the manifest over,
     *  and only then {@link DirtyIndexFeed#clear} the markers. {@code true} when this pass is handled - committed, or
     *  nothing to do - and {@code false} when it could not commit and the caller should rebuild fully. */
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
            // A reader over the pre-apply snapshot for the out-of-order guard, opened before the writer mutates the
            // directory.
            // The writer does not close the analyzer it was configured with, so the analyzer is closed after it.
            try (DirectoryReader before = DirectoryReader.open(directory);
                 Analyzer analyzer = CoordinateAnalyzer.fields()) {
                IndexSearcher guard = new IndexSearcher(before);
                IndexWriter writer = new IndexWriter(directory,
                        new IndexWriterConfig(analyzer).setOpenMode(IndexWriterConfig.OpenMode.APPEND));
                try {
                    // Paged through the feed's drain into one writer; nothing clears until the snapshot below commits.
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
            try (DirectoryReader after = DirectoryReader.open(directory)) {
                documents = after.numDocs();
            }
            Optional<String> checksum = index.writeSnapshot(generation, directory);
            if (checksum.isEmpty()) {
                return false;   // a concurrent rebuild claimed this generation and cuts over next; ours wrote only segments
            }
            SearchManifest manifest = new SearchManifest(generation, SearchIndex.FORMAT, documents, checksum.get(),
                    current.reconciled());
            if (!index.putManifest(manifest, token)) {
                // A concurrent cutover won: drop this generation and rebuild fully; the feed is not cleared, so nothing
                // is lost.
                SearchManifest winner = index.manifestVersioned()
                        .map(versioned -> SearchManifest.parse(versioned.content())).orElse(null);
                if (winner == null || winner.generation() != generation) {
                    index.deleteSnapshot(generation);
                }
                return false;
            }
        } finally {
            directory.close();
        }
        // The snapshot committed, so the applied markers go now; a coordinate re-touched meanwhile survives, since
        // clear is token-fenced.
        feed.clear(applied);
        gcSuperseded(index, generation);
        gauges(context, index, generation, documents);
        return true;
    }

    /** Apply one dirty marker, answering whether it was applied (and is cleared) or skipped by the out-of-order guard
     *  (and is left). A {@code removed} marker deletes the document; a touched one re-derives the coordinate's document
     *  from durable truth and upserts it, or deletes it when truth no longer carries the coordinate. */
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
            // A path-addressed artifact's truth is its serving pointer, one point read; gone means a delete.
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

    /** The version already indexed for a key, or {@code null} when absent, read against the pre-apply reader. */
    private static Long indexedVersion(IndexSearcher guard, Term keyTerm) throws IOException {
        TopDocs top = guard.search(new TermQuery(keyTerm), 1);
        if (top.scoreDocs.length == 0) {
            return null;
        }
        var field = guard.storedFields().document(top.scoreDocs[0].doc).getField("version");
        return field == null || field.numericValue() == null ? null : field.numericValue().longValue();
    }

    /** Re-derive one coordinate version's {@link Release} by the inventory's point read of its document, which keeps
     *  the apply O(&Delta;) rather than reading every version of a much-released coordinate per marker. */
    private static Release release(StoreRepositoryInventory inventory, String ecosystem, String coordinate,
                                  String version) throws IOException {
        return inventory.release(ecosystem, coordinate, version).orElse(null);
    }

    private void fullRebuild(ArtifactStore store, SearchIndex index, StoreRepositoryInventory inventory,
                             LicenseDerivation licenses, DirtyIndexFeed feed, SearchManifest current, Object token,
                             Gauges gauges) throws IOException {
        int generation = (current == null ? 0 : current.generation()) + 1;
        // The cutoff is taken before the enumeration reads truth: a change marked later has a newer version and stays
        // for the next incremental pass, while everything up to the cutoff is compacted after the commit.
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
            SearchManifest manifest = new SearchManifest(generation, SearchIndex.FORMAT, accumulation.documents,
                    checksum.get(), Instant.ofEpochMilli(cutoff));
            if (!index.putManifest(manifest, token)) {
                SearchManifest winner = index.manifestVersioned()
                        .map(versioned -> SearchManifest.parse(versioned.content())).orElse(null);
                if (winner == null || winner.generation() != generation) {
                    index.deleteSnapshot(generation);
                }
                return;
            }
            gcSuperseded(index, generation);
            // The rebuild reflects truth, so markers through the cutoff are compacted; a later one (a publish that
            // raced the rebuild) is kept.
            feed.compactThrough(cutoff);
            gauges.gauge("jenrepo.search.documents", "Documents in the search index", accumulation.documents);
            gauges.gauge("jenrepo.search.bytes", "Compressed size of the search index snapshot",
                    index.snapshotSize(generation));
        } finally {
            accumulation.release();   // the scratch directory: its files are linked into the cache, or the build is abandoned
        }
    }

    /** The walk-less build: a streaming walk of the version documents, each release folded into the writer as it
     *  flows. */
    private static Accumulation accumulateStreaming(StoreRepositoryInventory inventory, LicenseDerivation licenses)
            throws IOException {
        Accumulation accumulation = new Accumulation(inventory, licenses);
        try (accumulation) {
            inventory.releases(accumulation);
            inventory.servedPaths(path -> accumulation.visitServedPath(inventory, path));
        } catch (IOException | RuntimeException failed) {
            accumulation.release();   // abandoned: nothing will link its files
            throw failed;
        }
        return accumulation;
    }

    /** The walk-riding build: stream the releases over this task's {@code walks/search} pass, answering the
     *  accumulation only when this run provably saw the whole repository. */
    private Accumulation accumulateOverWalk(ArtifactStore store, StoreRepositoryInventory inventory,
                                            LicenseDerivation licenses) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            boolean fresh = walk.pass(store, CONSUMER).map(WalkPass::complete).orElse(true);
            Accumulation accumulation = new Accumulation(inventory, licenses);
            WalkPass pass;
            try (accumulation) {
                pass = inventory.releases(walk, CONSUMER, accumulation,
                        path -> accumulation.visitServedPath(inventory, path));
            } catch (IOException | RuntimeException failed) {
                accumulation.release();   // abandoned: nothing will link its files
                throw failed;
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

    /** The pass's gauges on its registry; the walk consumer reports them to the observability report instead. */
    private static void gauges(RepositoryContext context, SearchIndex index, int generation, long documents)
            throws IOException {
        Map<String, String> tags = Map.of("tenant", context.tenant(), "repository", context.repository());
        context.gauge("jenrepo.search.documents", "Documents in the search index", tags, documents);
        context.gauge("jenrepo.search.bytes", "Compressed size of the search index snapshot", tags,
                index.snapshotSize(generation));
    }

    /** Whether every segment of {@code generation} is done and one worker finished them all: proof that one walk
     *  enumerated the whole pass. */
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

    private static void gcSuperseded(SearchIndex index, int generation) throws IOException {
        for (int superseded : index.generations()) {
            if (superseded != generation && superseded <= generation - KEEP_GENERATIONS) {
                index.deleteSnapshot(superseded);
            }
        }
        index.gcSegments();   // a segment file no remaining generation names
    }


    /** Whether the pass's own reconcile is due: {@value #RECONCILE} is set and that long has passed since the
     *  manifest's last reconcile. Unset or unreadable, never. */
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

    /** The term a release document is keyed by: {@code ecosystem/coordinate/version} joined with a separator no
     *  coordinate carries, so it round-trips. {@link SearchPublicationObserver} marks the feed with the same key. */
    public static String coordinateKey(String ecosystem, String coordinate, String version) {
        return (ecosystem == null ? "" : ecosystem) + SEPARATOR + coordinate + SEPARATOR + version;
    }

    /** The search hit an indexed document is: a path-addressed artifact by its path, a coordinate version by its key's
     *  parts, since a version containing a colon would mis-split the display. */
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

    /** Split a coordinate key into {@code {ecosystem, coordinate, version}}, or {@code null} when it is not a
     *  three-part key. */
    static String[] parseKey(String key) {
        String[] parts = key.split(SEPARATOR, -1);
        return parts.length == 3 ? parts : null;
    }

    /** The index key of a path-addressed artifact, one served by request path with no coordinate, such as a raw upload.
     *  Two parts where a coordinate key has three, so the key spaces cannot collide. Indexing them is what lets search
     *  answer without walking the store per request. */
    static String pathKey(String requestPath) {
        return PATH_MARKER + SEPARATOR + requestPath;
    }

    /** The request path a {@link #pathKey} names, or {@code null} when the key is not one. */
    static String parsePathKey(String key) {
        String[] parts = key.split(SEPARATOR, -1);
        return parts.length == 2 && PATH_MARKER.equals(parts[0]) ? parts[1] : null;
    }

    /** The first part of a path key; a request path begins with {@code /}, so neither can be mistaken for an
     *  ecosystem. */
    private static final String PATH_MARKER = "path";

    /** The unit separator (U+001F) joining a key's parts: no ecosystem, coordinate or version carries it. */
    private static final String SEPARATOR = "\u001f";

    /** One full rebuild's state: the file-backed scratch directory its writer fills, never a heap-held index. */
    private static final class Accumulation implements RepositoryInventory.ReleaseVisitor, Closeable {

        private final Directory directory = SearchIndex.scratch();
        /** The analyzer the writer was configured with, which closing the writer leaves open. */
        private final Analyzer analyzer = CoordinateAnalyzer.fields();
        private final IndexWriter writer;
        private final StoreRepositoryInventory inventory;
        private final LicenseDerivation licenses;
        private long documents;

        private Accumulation(StoreRepositoryInventory inventory, LicenseDerivation licenses) throws IOException {
            this.inventory = inventory;
            this.licenses = licenses;
            try {
                writer = new IndexWriter(directory, new IndexWriterConfig(analyzer));
            } catch (IOException | RuntimeException failed) {
                analyzer.close();
                SearchIndex.discard(directory);   // nothing was written into it, and no one else holds it
                throw failed;
            }
        }

        @Override
        public void visit(Release release) throws IOException {
            String key = coordinateKey(release.ecosystem(), release.coordinate(), release.version());
            long version = release.published() == null ? 0 : release.published().toEpochMilli();
            Document document = document(key, release, inventory, licenses, version);
            writer.addDocument(document);
            documents++;
        }

        /** One served request path, indexed only when no coordinate describes it: a published package's files are
         *  already in the index under its coordinate. Asked of the format through {@code pathAddressed}, not
         *  {@code describe}, which is also empty for a path a layout claims but cannot parse. One point read per
         *  pointer, in the background, rather than a walk per search request. */
        void visitServedPath(StoreRepositoryInventory inventory, String requestPath) throws IOException {
            if (!inventory.pathAddressed(requestPath)) {
                return;
            }
            writer.addDocument(document(pathKey(requestPath), requestPath, 0L));
            documents++;
        }

        @Override
        public void close() throws IOException {
            try {
                writer.close();
            } finally {
                analyzer.close();
            }
        }

        /** Release the scratch directory, once its files are linked into the cache or the build is abandoned. */
        void release() throws IOException {
            SearchIndex.discard(directory);
        }
    }

    /** One path-addressed document: its display is the served request path. The leading {@code /} tells it apart, since
     *  a {@code coordinate:version} display never starts with one, and the two are screened differently -
     *  {@code disclosableDisplay} refuses a bare name. No licence fields, so it drops out of a {@code license:} or
     *  {@code category:} query. */
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

    /** One coordinate version: the {@code key} term, the numeric {@code version} the out-of-order guard reads, the
     *  {@code coordinate:version} display as a stored term with its sorted twin, the ecosystem and coordinate as the
     *  name a person types, the text a person remembers - the version, and the manifest's description, keywords and
     *  credited people - and the declared licences as filter terms, all from one read of the version's document. A
     *  version published before the manifest's fields were recorded is found by its name. */
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
        }
        for (String category : categories) {
            document.add(new StringField("category", category, Field.Store.NO));   // category values are lower-case
        }
        return document;
    }
}
