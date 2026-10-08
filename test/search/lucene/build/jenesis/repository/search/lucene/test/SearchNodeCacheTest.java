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
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.ForwardingArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.inventory.StoreRepositoryInventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the search index leaves on a node's disk. A node that only reads keeps the files of the generation it serves
 * and the one before, however many it has served; and a pass that fails part way through applying the change feed
 * commits nothing into the next generation's directory and holds no file of the cache open afterwards.
 */
class SearchNodeCacheTest {

    private static final Duration INTERVAL = Duration.ofMinutes(10);
    private static final Instant NOW = Instant.parse("2026-02-01T00:00:00Z");

    @TempDir
    Path root;

    @Test
    void a_reading_node_keeps_two_generations_of_files_however_many_it_served() throws IOException {
        ArtifactStore store = store();
        Assumptions.assumeTrue(linkCounts(), "the file system reports no link count");
        // The pass runs as another node would: over the same store, caching under another identity.
        ArtifactStore elsewhere = new ForwardingArtifactStore(store) {
            @Override
            public ArtifactStore scope(String segment) {
                return store.scope(segment);
            }

            @Override
            public Object identity() {
                return "another node's view of " + store.identity();
            }
        };
        LuceneSearchQueryProvider provider = new LuceneSearchQueryProvider(Duration.ZERO);
        SearchQuery query = provider.over(store, "default/app");
        SearchPublicationObserver observer = new SearchPublicationObserver();
        publish(store, "org.example:a");
        sweep(elsewhere);
        assertThat(hits(query)).containsExactly("org.example:a:1.0");
        for (String next : List.of("b", "c", "d")) {
            publish(store, "org.example:" + next);
            observer.onPublished(descriptor("org.example:" + next), store);
            sweep(elsewhere);
            assertThat(hits(query)).contains("org.example:" + next + ":1.0");
        }
        Path cache = cache(store);
        // Every cached segment written long enough ago that pruning may take it once no generation links it.
        try (Stream<Path> segments = Files.list(cache.resolve("segments"))) {
            for (Path segment : segments.toList()) {
                Files.setLastModifiedTime(segment, FileTime.from(Instant.now().minus(Duration.ofHours(1))));
            }
        }

        publish(store, "org.example:e");
        observer.onPublished(descriptor("org.example:e"), store);
        sweep(elsewhere);
        assertThat(hits(query)).contains("org.example:e:1.0");

        try (Stream<Path> entries = Files.list(cache)) {
            assertThat(entries.map(entry -> entry.getFileName().toString()).filter(name -> name.startsWith("generation-")))
                    .as("the generation served and the one before").containsExactlyInAnyOrder("generation-4",
                            "generation-5");
        }
        try (Stream<Path> segments = Files.list(cache.resolve("segments"))) {
            List<Path> cached = segments.toList();
            assertThat(cached).isNotEmpty();
            for (Path segment : cached) {
                assertThat((Integer) Files.getAttribute(segment, "unix:nlink"))
                        .as("%s is a file of a generation kept", segment.getFileName()).isGreaterThan(1);
            }
        }
        provider.close();
    }

    @Test
    void a_pass_failing_part_way_through_the_feed_commits_nothing_and_holds_nothing_open() throws IOException {
        ArtifactStore store = store();
        SearchPublicationObserver observer = new SearchPublicationObserver();
        publish(store, "org.example:a");
        sweep(store);
        Path cache = cache(store);
        // A reader links the generation's files, and its searcher is closed before the pass runs.
        LuceneSearchQueryProvider reading = new LuceneSearchQueryProvider(Duration.ZERO);
        assertThat(hits(reading.over(store, "default/app"))).containsExactly("org.example:a:1.0");
        reading.close();
        Set<String> first = files(cache.resolve("generation-1"));
        assertThat(open(cache)).as("before the pass").isEmpty();

        publish(store, "org.example:b");
        observer.onPublished(descriptor("org.example:b"), store);
        publish(store, "org.example:zzz-fail");
        observer.onPublished(descriptor("org.example:zzz-fail"), store);
        ArtifactStore failing = new ForwardingArtifactStore(store) {
            @Override
            public ArtifactStore scope(String segment) {
                return store.scope(segment);
            }

            @Override
            public void read(String key, OutputStream out) throws IOException {
                refuse(key);
                super.read(key, out);
            }

            @Override
            public InputStream open(String key) throws IOException {
                refuse(key);
                return super.open(key);
            }

            @Override
            public Optional<Versioned> readVersioned(String key) throws IOException {
                refuse(key);
                return super.readVersioned(key);
            }

            private void refuse(String key) throws IOException {
                if (key.contains("zzz-fail") && !key.startsWith("index/")) {
                    throw new IOException("the store went away reading " + key);
                }
            }
        };

        assertThatThrownBy(() -> sweep(failing)).isInstanceOf(IOException.class);

        assertThat(files(cache.resolve("generation-2"))).as("the next generation holds what the last one did")
                .isEqualTo(first);
        assertThat(open(cache)).as("no file of the node's cache is left open").isEmpty();

        sweep(store);
        LuceneSearchQueryProvider provider = new LuceneSearchQueryProvider(Duration.ZERO);
        assertThat(hits(provider.over(store, "default/app"))).as("the next pass applies the feed whole")
                .containsExactly("org.example:a:1.0", "org.example:b:1.0", "org.example:zzz-fail:1.0");
        provider.close();
        assertThat(open(cache)).as("and a closed searcher holds nothing open either").isEmpty();
    }

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("default").scope("app");
    }

    /** The node's cache for {@code store}, where the index keeps it. */
    private static Path cache(ArtifactStore store) {
        try {
            String identity = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    String.valueOf(store.identity()).getBytes(StandardCharsets.UTF_8))).substring(0, 16);
            return Path.of(System.getProperty("java.io.tmpdir"), "jenrepo-search", identity);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The Lucene files of a link directory, its lock aside. */
    private static Set<String> files(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).filter(name -> !name.startsWith("write.lock"))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    /** The files under {@code cache} this process holds open, as Linux reports them; none where it does not. */
    private static List<String> open(Path cache) throws IOException {
        Path descriptors = Path.of("/proc/self/fd");
        if (!Files.isDirectory(descriptors)) {
            return List.of();
        }
        List<String> held = new ArrayList<>();
        try (Stream<Path> fds = Files.list(descriptors)) {
            for (Path fd : fds.toList()) {
                try {
                    Path target = Files.readSymbolicLink(fd);
                    if (target.startsWith(cache)) {
                        held.add(target.toString());
                    }
                } catch (IOException gone) {
                    // closed while listed
                }
            }
        }
        return held;
    }

    private boolean linkCounts() throws IOException {
        try {
            return Files.getAttribute(root, "unix:nlink") instanceof Integer;
        } catch (UnsupportedOperationException | IllegalArgumentException unsupported) {
            return false;
        }
    }

    private static void publish(ArtifactStore store, String coordinate) throws IOException {
        String path = "/maven/" + coordinate + "/1.0/artifact";
        String hash = store.writeBlob(new ByteArrayInputStream(path.getBytes(StandardCharsets.UTF_8)));
        new Publication(store).link(path, hash);
        new StoreRepositoryInventory(store).record("maven", coordinate, "1.0", false, NOW);
    }

    private static ArtifactDescriptor descriptor(String coordinate) {
        return new ArtifactDescriptor("maven", coordinate, "1.0", "/maven/" + coordinate + "/1.0/artifact", null,
                false, "hash", 1L);
    }

    private static List<String> hits(SearchQuery query) throws IOException {
        return query.search("", null, SearchQuery.MAX_PAGE).orElseThrow().hits().stream()
                .map(SearchQuery.Hit::display).toList();
    }

    private static void sweep(ArtifactStore store) throws IOException {
        new SearchIndexTask(INTERVAL).repository(new RepositoryContext() {
            @Override
            public TenantView tenantView() {
                return TenantView.NONE;
            }

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
                return key -> SearchMode.SETTING.equals(key) ? "true" : null;
            }

            @Override
            public Instant now() {
                return NOW;
            }

            @Override
            public void gauge(String name, String description, Map<String, String> tags, double value) {
            }
        });
    }
}
