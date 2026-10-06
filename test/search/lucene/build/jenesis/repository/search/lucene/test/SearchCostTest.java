package build.jenesis.repository.search.lucene.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.maintenance.UnitFailures;
import build.jenesis.repository.search.SearchMode;
import build.jenesis.repository.search.lucene.SearchIndexTask;
import build.jenesis.repository.search.lucene.SearchPublicationObserver;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.DocumentMemory;
import build.jenesis.repository.store.MissMemory;
import build.jenesis.repository.store.NodeMemoStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.testkit.FaultInjectingStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the full-text index costs the store, held by counting its operations, so a field or a pass added later cannot
 * quietly raise the bill. Off, the index costs nothing: the pass reads nothing for a repository that has not asked for
 * one, and a publish into it costs at most one existence probe per miss-memory window. On, an idle pass reads two small
 * objects and writes nothing, a publish writes one change marker, and a rebuild reads each release's document twice -
 * once for the release, once for what the index carries beside it - and writes a number of objects that does not grow
 * with the repository.
 */
class SearchCostTest {

    private static final Instant NOW = Instant.parse("2026-09-30T00:00:00Z");

    private static final Set<FaultInjectingStore.Op> WRITES = EnumSet.of(FaultInjectingStore.Op.WRITE,
            FaultInjectingStore.Op.WRITE_BLOB, FaultInjectingStore.Op.WRITE_VERSIONED, FaultInjectingStore.Op.DELETE);

    @TempDir
    Path root;

    private final List<FaultInjectingStore.Op> operations = new CopyOnWriteArrayList<>();

    private final List<String> traced = new CopyOnWriteArrayList<>();

    private ArtifactStore backing(String repository) {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("default")
                .scope(repository);
    }

    private FaultInjectingStore counted(ArtifactStore store) {
        return FaultInjectingStore.wrap(store).tracing((op, key) -> {
            operations.add(op);
            traced.add(op + " " + family(key));
        });
    }

    private void publish(ArtifactStore store, String coordinate, String version) throws IOException {
        String path = "/maven/" + coordinate + "/" + version + "/artifact";
        String hash = store.writeBlob(new ByteArrayInputStream(path.getBytes(StandardCharsets.UTF_8)));
        new Publication(store).link(path, hash);
        new StoreRepositoryInventory(store).record("maven", coordinate, version, false, NOW);
    }

    private static ArtifactDescriptor descriptor(String coordinate, String version) {
        return new ArtifactDescriptor("maven", coordinate, version, "/maven/" + coordinate + "/" + version + "/artifact",
                null, false, "hash", 1L);
    }

    private static String family(String key) {
        if (key == null) {
            return "-";
        }
        String[] parts = key.split("/");
        return parts.length < 2 ? parts[0] : parts[0] + "/" + parts[1];
    }

    private long reads() {
        return operations.stream().filter(op -> !WRITES.contains(op)).count();
    }

    private long writes() {
        return operations.stream().filter(WRITES::contains).count();
    }

    @Test
    void a_repository_with_full_text_off_costs_the_pass_nothing() throws IOException {
        ArtifactStore store = backing("off");
        publish(store, "org.example:lib", "1.0");
        operations.clear();

        new SearchIndexTask(Duration.ofMinutes(10)).repository(context(counted(store), false));

        assertThat(operations).as("not a read, not a write").isEmpty();
    }

    @Test
    void an_idle_pass_reads_the_manifest_and_one_page_of_the_feed_and_writes_nothing() throws IOException {
        ArtifactStore store = backing("idle");
        publish(store, "org.example:lib", "1.0");
        new SearchIndexTask(Duration.ofMinutes(10)).repository(context(store, true));     // the bootstrap
        operations.clear();

        for (int pass = 0; pass < 3; pass++) {
            new SearchIndexTask(Duration.ofMinutes(10)).repository(context(counted(store), true));
        }

        assertThat(writes()).as("an idle pass writes nothing, however many run").isZero();
        assertThat(reads()).as("the manifest and one page of the pending markers, per pass").isEqualTo(3 * 2);
    }

    @Test
    void a_publish_into_a_repository_with_no_index_costs_at_most_a_probe_per_miss_window() throws IOException {
        ArtifactStore plain = backing("unindexed");
        SearchPublicationObserver observer = new SearchPublicationObserver();

        observer.onPublished(descriptor("org.example:lib", "1.0"), counted(plain));
        assertThat(writes()).as("no marker where there is no index").isZero();
        assertThat(reads()).as("one existence probe of the manifest").isEqualTo(1);

        operations.clear();
        ArtifactStore remembering = NodeMemoStore.over(counted(plain), new MissMemory(Duration.ofMinutes(1)),
                new DocumentMemory(Duration.ZERO));
        for (int file = 0; file < 20; file++) {
            observer.onPublished(descriptor("org.example:lib", "1." + file), remembering);
        }
        assertThat(operations).as("a build deploying twenty files probes once, then the node remembers")
                .hasSize(1);
    }

    @Test
    void a_publish_into_a_repository_with_an_index_writes_one_marker() throws IOException {
        ArtifactStore store = backing("indexed");
        publish(store, "org.example:lib", "1.0");
        new SearchIndexTask(Duration.ofMinutes(10)).repository(context(store, true));     // the bootstrap
        operations.clear();

        new SearchPublicationObserver().onPublished(descriptor("org.example:lib", "2.0"), counted(store));

        assertThat(writes()).as("one change marker").isEqualTo(1);
        assertThat(reads()).as("the existence probe, and the marker's own compare-and-set read").isLessThanOrEqualTo(2);
    }

    @Test
    void a_rebuild_reads_a_fixed_few_objects_per_release_and_writes_the_same_whatever_the_repository_holds()
            throws IOException {
        Map<String, Long> small = rebuild("small", 20);
        Map<String, Long> large = rebuild("large", 200);
        Map<String, Double> perRelease = new TreeMap<>();
        for (String family : large.keySet()) {
            perRelease.put(family, (large.get(family) - small.getOrDefault(family, 0L)) / 180.0);
        }

        assertThat(perRelease).as("per further release, operation and key family: %s", perRelease)
                // The release's document: twice for the release row the enumeration hands over - its publish facts
                // and its download facts - and once for what the index carries beside it, the licences and what the
                // manifest said, in the one read the licences alone took before. Nothing is read from the artifact.
                .containsEntry("READ_VERSIONED meta/maven", 3.0)
                // The enumeration's own listing: the coordinate's folder of version documents, and the two folders of
                // its served path, which the rebuild walks for artifacts that have no coordinate.
                .containsEntry("PAGE meta/maven", 1.0)
                .containsEntry("EXISTS meta/maven", 1.0)
                .containsEntry("PAGE publish/maven", 2.0)
                .containsEntry("EXISTS publish/maven", 2.0);
        assertThat(perRelease.values().stream().mapToDouble(Double::doubleValue).sum())
                .as("and nothing else grows with the repository").isEqualTo(9.0);
        assertThat(large.entrySet()).filteredOn(entry -> entry.getKey().endsWith("index/search"))
                .as("the index's own reads and writes - its segments, generation and manifest - do not grow "
                        + "with the releases it indexes")
                .containsExactlyInAnyOrderElementsOf(small.entrySet().stream()
                        .filter(entry -> entry.getKey().endsWith("index/search")).toList());
    }

    /** A bootstrap over {@code releases} releases, published as the gate records them - with their licences - and
     *  what it cost, by operation and key family. */
    private Map<String, Long> rebuild(String repository, int releases) throws IOException {
        ArtifactStore store = backing(repository);
        for (int index = 0; index < releases; index++) {
            String coordinate = String.format(Locale.ROOT, "org.example:lib%04d", index);
            String path = "/maven/" + coordinate + "/1.0/artifact";
            new Publication(store).link(path,
                    store.writeBlob(new ByteArrayInputStream(path.getBytes(StandardCharsets.UTF_8))));
            new StoreRepositoryInventory(store).recording("maven", coordinate, "1.0", false, NOW)
                    .licenses(List.of()).commit();
        }
        traced.clear();
        new SearchIndexTask(Duration.ofMinutes(10)).repository(context(counted(store), true));
        Map<String, Long> byFamily = new TreeMap<>();
        traced.forEach(entry -> byFamily.merge(entry, 1L, Long::sum));
        return byFamily;
    }

    private static RepositoryContext context(ArtifactStore store, boolean fullText) {
        return new RepositoryContext() {

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
                return key -> SearchMode.SETTING.equals(key) ? Boolean.toString(fullText) : null;
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
}
