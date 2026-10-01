package build.jenesis.repository.search.lucene.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.maintenance.UnitFailures;
import build.jenesis.repository.search.SearchMode;
import build.jenesis.repository.search.SearchQuery;
import build.jenesis.repository.search.lucene.LuceneSearchQueryProvider;
import build.jenesis.repository.search.lucene.SearchIndexTask;
import build.jenesis.repository.search.lucene.SearchPublicationObserver;
import build.jenesis.repository.search.lucene.SearchRebuildConsumer;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.DirtyIndexFeed;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.inventory.LicenseInventory;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.walk.RebuildPass;
import build.jenesis.repository.walk.WalkProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The change-fed search sweep. Proves the O(&Delta;) steady state over the free
 * {@link DirtyIndexFeed} + {@code PublicationObserver.onDeleted}: an add / update / delete is reflected after an
 * incremental sweep without a full rebuild; {@code onDeleted} removes the doc; the periodic reconcile heals an
 * injected drift the feed missed; an out-of-order event never regresses a newer doc; a marker replayed after a crash
 * (before the cursor advanced) is idempotent; a format-version bump still full-rebuilds; and a sweep after one publish
 * parses only the changed coordinate, not the whole set (proven with a read-counting store).
 */
class SearchIncrementalTest {

    private static final Duration INTERVAL = Duration.ofMinutes(10);
    private static final Instant NOW = Instant.parse("2026-02-01T00:00:00Z");

    @TempDir
    Path root;

    private ArtifactStore store(String tenant, String repository) {
        return ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null)
                .scope(tenant).scope(repository);
    }

    private void publish(ArtifactStore store, String ecosystem, String coordinate, String version) throws IOException {
        String path = "/" + ecosystem + "/" + coordinate + "/" + version + "/artifact";
        String hash = store.writeBlob(new ByteArrayInputStream(path.getBytes(StandardCharsets.UTF_8)));
        new Publication(store).link(path, hash);
        new StoreRepositoryInventory(store).record(ecosystem, coordinate, version, false, NOW);
    }

    private void licenseSidecar(ArtifactStore store, String ecosystem, String coordinate, String version, String name)
            throws IOException {
        new LicenseInventory(store).record(ecosystem, coordinate, version,
                List.of(new LicenseInventory.Declared(name, null)));
    }

    private ArtifactDescriptor descriptor(String ecosystem, String coordinate, String version) {
        return new ArtifactDescriptor(ecosystem, coordinate, version,
                "/" + ecosystem + "/" + coordinate + "/" + version + "/artifact", null, false, "hash", 1L);
    }

    private void sweep(ArtifactStore store) throws IOException {
        sweep(store, Map.of());
    }

    private void sweep(ArtifactStore store, Map<String, String> config) throws IOException {
        new SearchIndexTask(INTERVAL).repository(context(store, config));
    }

    private SearchQuery query(ArtifactStore store, String scope) {
        return new LuceneSearchQueryProvider(Duration.ZERO).over(store, scope);
    }

    // ---- the tests ----------------------------------------------------------------------------------------------

    /**
     * A path-addressed artifact - a raw upload, with no coordinate to be indexed under - reaches the index through
     * the same incremental sweep, under its served path.
     *
     * <p>It could not before: the observer returned early for any descriptor carrying no coordinate, so the only
     * way {@code /api/search} could find one was to walk the published tree live on every request, up to twenty
     * thousand names examined and up to four store reads under each. That is nothing over a directory and minutes
     * over an object store, and it is what made a search on an Azurite-backed node outlive a sixty-second client
     * timeout while the node was healthy and its index idle.
     *
     * <p>The display is the request path itself, which is what search has always returned for these and what a
     * caller fetches one by. Its leading {@code /} is what tells the two document kinds apart at a screening
     * surface: a request path always begins with one and a {@code coordinate:version} display never does.
     */
    /**
     * A search expression matches as a prefix of the whole display, so a namespace can be listed by typing it.
     *
     * <p>The analysed half of the query matches token prefixes anywhere in a coordinate, which finds an artifact
     * but cannot list a namespace: it never looks at WHERE in the display the tokens fall, so {@code com.acme}
     * ranks a {@code com}-and-{@code acme} match in another group equally. Matching the display itself as a prefix
     * is what turns a dotted expression into "everything under here", and it works the same way for a served path,
     * which is the shape a raw upload is found by.
     */
    @Test
    void a_query_lists_every_entry_whose_display_starts_with_it() throws IOException {
        ArtifactStore store = store("default", "prefix");
        publish(store, "maven", "com.acme:lib", "1.0");
        publish(store, "maven", "com.acme.util:tool", "2.0");
        publish(store, "maven", "org.other:acme-thing", "3.0");
        String path = "/raw/notes/release-notes.txt";
        String hash = store.writeBlob(new ByteArrayInputStream("notes".getBytes(StandardCharsets.UTF_8)));
        new Publication(store).link(path, hash);
        sweep(store);
        new SearchPublicationObserver().onPublished(
                new ArtifactDescriptor("raw", null, null, path, null, false, hash, 5L), store);
        sweep(store);

        assertThat(hits(query(store, "default/prefix"), "com.acme"))
                .as("the namespace lists, and the unrelated coordinate whose TOKENS match does not lead it")
                .containsExactly("com.acme.util:tool:2.0", "com.acme:lib:1.0");
        assertThat(hits(query(store, "default/prefix"), "/raw/notes"))
                .as("a served path is listed by its prefix too")
                .containsExactly(path);
        assertThat(hits(query(store, "default/prefix"), "org.other"))
                .as("and a prefix that names one entry lists that one")
                .containsExactly("org.other:acme-thing:3.0");
    }

    @Test
    void a_path_addressed_artifact_is_indexed_under_its_served_path() throws IOException {
        ArtifactStore store = store("default", "raw");
        publish(store, "maven", "org.example:a", "1.0");
        sweep(store);                                                         // bootstrap: the index now exists
        assertThat(hits(query(store, "default/raw"), "")).containsExactly("org.example:a:1.0");

        // A raw upload: bytes linked at a served path, with nothing recorded in the coordinate inventory.
        String path = "/raw/notes/release-notes.txt";
        String hash = store.writeBlob(new ByteArrayInputStream("notes".getBytes(StandardCharsets.UTF_8)));
        new Publication(store).link(path, hash);
        new SearchPublicationObserver().onPublished(
                new ArtifactDescriptor("raw", null, null, path, null, false, hash, 5L), store);
        sweep(store);

        assertThat(hits(query(store, "default/raw"), ""))
                .as("the raw upload is in the index beside the coordinate, under its served path")
                .containsExactly(path, "org.example:a:1.0");
        assertThat(hits(query(store, "default/raw"), "release-notes"))
                .as("and it is findable by a term of its path, which is what the live walk answers")
                .containsExactly(path);
    }

    @Test
    void an_add_update_and_delete_are_reflected_after_an_incremental_sweep() throws IOException {
        ArtifactStore store = store("default", "app");
        publish(store, "maven", "org.example:a", "1.0");
        licenseSidecar(store, "maven", "org.example:a", "1.0", "MIT License");
        sweep(store);                                                         // bootstrap: full rebuild, index exists
        assertThat(hits(query(store, "default/app"), "")).containsExactly("org.example:a:1.0");

        SearchPublicationObserver observer = new SearchPublicationObserver();

        // ADD - a new coordinate, marked through the observer, applied by the incremental sweep (no full rebuild).
        publish(store, "maven", "org.example:b", "1.0");
        observer.onPublished(descriptor("maven", "org.example:b", "1.0"), store);
        sweep(store);
        assertThat(hits(query(store, "default/app"), ""))
                .containsExactly("org.example:a:1.0", "org.example:b:1.0");

        // UPDATE - re-touching a coordinate upserts it by term (updateDocument), so the document is REPLACED, never
        // duplicated - the index still carries exactly one document per coordinate after the re-touch.
        observer.onPublished(descriptor("maven", "org.example:a", "1.0"), store);
        sweep(store);
        assertThat(hits(query(store, "default/app"), ""))
                .as("re-touching a coordinate upserts by term - one document, no duplicate")
                .containsExactly("org.example:a:1.0", "org.example:b:1.0");

        // DELETE - onDeleted removes the doc by term.
        observer.onDeleted(descriptor("maven", "org.example:b", "1.0"), store);
        sweep(store);
        assertThat(hits(query(store, "default/app"), "")).containsExactly("org.example:a:1.0");
    }

    @Test
    void on_deleted_removes_the_doc() throws IOException {
        ArtifactStore store = store("default", "app");
        publish(store, "maven", "org.example:gone", "1.0");
        sweep(store);
        assertThat(hits(query(store, "default/app"), "gone")).containsExactly("org.example:gone:1.0");

        new SearchPublicationObserver().onDeleted(descriptor("maven", "org.example:gone", "1.0"), store);
        sweep(store);
        assertThat(hits(query(store, "default/app"), "gone")).as("onDeleted removed the doc from the index").isEmpty();
    }

    @Test
    void the_reconcile_heals_a_drift_the_feed_missed() throws IOException {
        ArtifactStore store = store("default", "app");
        publish(store, "maven", "org.example:a", "1.0");
        sweep(store);                                                         // bootstrap

        // A drift the change feed never saw: a coordinate published straight to truth (an import / manual store edit)
        // with NO observer mark. An incremental sweep cannot see it.
        publish(store, "maven", "org.example:drift", "1.0");
        sweep(store);                                                         // incremental: default cadence
        assertThat(hits(query(store, "default/app"), ""))
                .as("the incremental sweep misses a coordinate the feed never marked")
                .containsExactly("org.example:a:1.0");

        // The pass's own reconcile rebuilds from truth and heals it, once its interval has passed since the last one.
        sweep(store, Map.of("search-reconcile-interval", "PT0.001S"));
        assertThat(hits(query(store, "default/app"), ""))
                .as("the reconcile rebuilds from truth and picks up the drifted coordinate")
                .containsExactly("org.example:a:1.0", "org.example:drift:1.0");
    }

    @Test
    void the_walk_rebuilds_the_index_from_truth_when_a_pass_carrying_the_consumer_completes() throws IOException {
        ArtifactStore store = store("default", "app");
        publish(store, "maven", "org.example:a", "1.0");
        sweep(store);                                                         // bootstrap
        publish(store, "maven", "org.example:drift", "1.0");                  // unmarked: the feed never saw it
        sweep(store);                                                         // incremental, default cadence: never reconciles by count
        assertThat(hits(query(store, "default/app"), ""))
                .as("at the default cadence the sweep never rebuilds from truth on its own")
                .containsExactly("org.example:a:1.0");

        SearchRebuildConsumer consumer = new SearchRebuildConsumer();
        consumer.onRepository(store, key -> SearchMode.SETTING.equals(key) ? "true" : null);
        RebuildPass.run(WalkProvider.resolve(key -> null).orElseThrow(), store, new Publication(store),
                new RebuildPass.Roots(StoreRepositoryInventory.pointerRoots(), List.of(), List.of(), List.of()),
                List.of(consumer));

        assertThat(RebuildPass.failed(store)).as("the rebuild consumer completed").isEmpty();
        assertThat(hits(query(store, "default/app"), ""))
                .as("the walk's completion rebuilt the index from truth and picked up the drifted coordinate")
                .containsExactly("org.example:a:1.0", "org.example:drift:1.0");
    }

    @Test
    void an_out_of_order_event_does_not_regress_a_newer_doc() throws IOException {
        ArtifactStore store = store("default", "app");
        publish(store, "maven", "org.example:a", "1.0");
        sweep(store);                                                         // bootstrap
        DirtyIndexFeed feed = new DirtyIndexFeed(store, "index/search");
        String key = SearchIndexTask.coordinateKey("maven", "org.example:a", "1.0");
        // Versions above the bootstrap document's version (its publish time), so these events order against it.
        long base = NOW.toEpochMilli();

        feed.touched(key, base + 200);                                       // a newer state is indexed at base+200
        sweep(store);
        assertThat(hits(query(store, "default/app"), "")).containsExactly("org.example:a:1.0");

        feed.removed(key, base + 100);                                       // a STALE delete arrives (base+100)
        sweep(store);
        assertThat(hits(query(store, "default/app"), ""))
                .as("the stale delete is skipped by the out-of-order guard - the newer doc survives")
                .containsExactly("org.example:a:1.0");

        feed.removed(key, base + 300);                                       // a genuinely newer delete (base+300)
        sweep(store);
        assertThat(hits(query(store, "default/app"), ""))
                .as("a delete newer than the indexed version applies").isEmpty();
    }

    @Test
    void a_marker_replayed_after_a_crash_before_the_cursor_advanced_is_idempotent() throws IOException {
        ArtifactStore store = store("default", "app");
        publish(store, "maven", "org.example:a", "1.0");
        sweep(store);                                                         // bootstrap
        DirtyIndexFeed feed = new DirtyIndexFeed(store, "index/search");
        String key = SearchIndexTask.coordinateKey("maven", "org.example:a", "1.0");
        long version = NOW.toEpochMilli() + 500;                             // above the bootstrap document's version

        feed.touched(key, version);
        sweep(store);                                                         // applies + clears the marker
        assertThat(hits(query(store, "default/app"), "")).containsExactly("org.example:a:1.0");

        // Simulate a crash that committed the snapshot but did NOT clear the marker (the cursor did not advance):
        // the same marker survives to the next sweep. The upsert-by-term is idempotent, so the replay is a no-op.
        feed.touched(key, version);
        sweep(store);
        assertThat(hits(query(store, "default/app"), ""))
                .as("replaying an already-applied marker leaves the index unchanged (no duplicate)")
                .containsExactly("org.example:a:1.0");
    }

    @Test
    void a_format_version_bump_still_full_rebuilds() throws IOException {
        ArtifactStore store = store("default", "app");
        publish(store, "maven", "org.example:a", "1.0");
        sweep(store);                                                         // bootstrap in the current format

        // A stale-format manifest (a future/old index layout this build cannot open) plus a coordinate the feed never
        // marked: the sweep must discard and full-rebuild from truth rather than incrementally apply an empty feed.
        Optional<ArtifactStore.Versioned> current = store.readVersioned("index/search/current");
        byte[] stale = ("jenesis-search 1\ngeneration " + 1 + "\nformat 1\ndocuments 1\nchecksum x\n")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(store.writeVersioned("index/search/current", stale, current.get().token())).isTrue();
        publish(store, "maven", "org.example:new", "1.0");                   // in truth only, unmarked

        sweep(store);
        assertThat(hits(query(store, "default/app"), ""))
                .as("a format mismatch full-rebuilds from truth, indexing the unmarked coordinate too")
                .containsExactly("org.example:a:1.0", "org.example:new:1.0");
    }

    @Test
    void a_sweep_after_one_publish_parses_only_the_changed_coordinate() throws IOException {
        ArtifactStore backing = store("default", "app");
        for (int n = 0; n < 6; n++) {
            publish(backing, "maven", "org.example:base" + n, "1.0");
        }
        sweep(backing);                                                       // bootstrap: reads all six (a full rebuild)

        // A seventh coordinate, marked through the observer. The incremental sweep must re-derive ONLY it from truth.
        publish(backing, "maven", "org.example:seventh", "1.0");
        new SearchPublicationObserver().onPublished(descriptor("maven", "org.example:seventh", "1.0"), backing);

        CountingStore counting = new CountingStore(backing);
        sweep(counting);                                                     // the O(delta) incremental sweep

        String root = StoreRepositoryInventory.publishedRoot() + "/";
        List<String> publishedReads = counting.reads.stream()
                .filter(key -> key.startsWith(root))
                .toList();
        assertThat(publishedReads)
                .as("the incremental sweep re-derives only the changed coordinate from the version documents")
                .isNotEmpty()
                .allSatisfy(key -> assertThat(key).contains("seventh"));
        assertThat(publishedReads)
                .as("no other coordinate's published facts are read - the sweep is O(delta), not O(N)")
                .noneMatch(key -> key.contains("base"));
        // And the delta landed.
        assertThat(hits(query(backing, "default/app"), "seventh")).containsExactly("org.example:seventh:1.0");
    }

    @Test
    void a_marker_on_one_version_reads_only_that_version_of_its_coordinate() throws IOException {
        ArtifactStore backing = store("default", "app");
        for (int n = 0; n < 6; n++) {
            publish(backing, "maven", "org.example:many", "1." + n);
        }
        sweep(backing);                                                       // bootstrap: reads all six versions
        // A seventh version, marked through the observer. The apply must read that version's own document and not
        // list its siblings to find it: on a coordinate released a hundred thousand times the listing cost a hundred
        // thousand reads per marker, which is what the search-incremental canary measured.
        publish(backing, "maven", "org.example:many", "2.0");
        new SearchPublicationObserver().onPublished(descriptor("maven", "org.example:many", "2.0"), backing);
        CountingStore counting = new CountingStore(backing);
        sweep(counting);
        String root = StoreRepositoryInventory.publishedRoot() + "/";
        List<String> versionReads = counting.reads.stream()
                .filter(key -> key.startsWith(root) && key.contains("many"))
                .toList();
        assertThat(versionReads)
                .as("the apply reads the marked version's document and no sibling's")
                .isNotEmpty()
                .allSatisfy(key -> assertThat(key).endsWith("/2.0"));
        assertThat(hits(query(backing, "default/app"), "many")).contains("org.example:many:2.0");
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private RepositoryContext context(ArtifactStore store, Map<String, String> config) {
        return new RepositoryContext() {
            @Override
            public UnitFailures failures(String work, String consequence) {
                return new UnitFailures(work, consequence);
            }

            @Override
            public String tenant() {
                return "default";
            }

            @Override
            public String repository() {
                return "app";
            }

            @Override
            public ArtifactStore store() {
                return store;
            }

            @Override
            public UnaryOperator<String> config() {
                // The repository asked for an index; a test may still switch it off by naming the setting.
                return key -> config.getOrDefault(key, SearchMode.SETTING.equals(key) ? "true" : null);
            }

            @Override
            public Instant now() {
                return NOW;
            }

            @Override
            public void gauge(String name, String description, Map<String, String> tags, double value) {
            }
        };
    }

    /** A read-recording {@link ArtifactStore} decorator: every key read (list, versioned read, open, read) is recorded
     *  so a test can prove the incremental sweep touched only the changed coordinate's keys - never the whole set. */
    private static final class CountingStore implements ArtifactStore {
        @Override
        public Object identity() {
            return delegate.identity();   // a decorator answers its delegate's subspace
        }

        private final ArtifactStore delegate;
        private final List<String> reads = Collections.synchronizedList(new ArrayList<>());

        private CountingStore(ArtifactStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public ArtifactStore scope(String segment) {
            return delegate.scope(segment);
        }

        @Override
        public boolean exists(String key) {
            reads.add(key);
            return delegate.exists(key);
        }

        @Override
        public void read(String key, OutputStream out) throws IOException {
            reads.add(key);
            delegate.read(key, out);
        }

        @Override
        public InputStream open(String key) throws IOException {
            reads.add(key);
            return delegate.open(key);
        }

        @Override
        public void write(String key, InputStream in) throws IOException {
            delegate.write(key, in);
        }

        @Override
        public String writeBlob(InputStream in) throws IOException {
            return delegate.writeBlob(in);
        }

        @Override
        public long size(String key) throws IOException {
            reads.add(key);
            return delegate.size(key);
        }

        @Override
        public void delete(String key) throws IOException {
            delegate.delete(key);
        }

        @Override
        public List<String> list(String prefix) {
            reads.add(prefix);
            return delegate.list(prefix);
        }

        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            reads.add(key);
            return delegate.readVersioned(key);
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            return delegate.writeVersioned(key, content, expected);
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return delegate.scan(prefix, startAfter, limit, consumer);
    }
}

    /** The coordinates one full page of {@code query} matches, or {@code null} when this repository has no usable
     *  index yet - which the SPI now says with an empty {@link Optional} rather than a {@code null} list.
     *  Every assertion below is about the rows, so the page is unwrapped here once. */
    private static List<String> hits(SearchQuery query, String text) throws IOException {
        return query.search(text, null, SearchQuery.MAX_PAGE)
                .map(page -> page.hits().stream().map(SearchQuery.Hit::display).toList()).orElse(null);
    }


}
