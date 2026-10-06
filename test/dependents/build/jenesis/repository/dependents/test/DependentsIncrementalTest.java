package build.jenesis.repository.dependents.test;

import module java.base;
import module org.junit.jupiter.api;
import java.util.jar.Attributes;
import build.jenesis.repository.dependents.DependentsIndex;
import build.jenesis.repository.dependents.DependentsIndexTask;
import build.jenesis.repository.dependents.DependentsPublicationObserver;
import build.jenesis.repository.dependents.DependentsRebuildConsumer;
import build.jenesis.repository.dependents.spi.DependentsQuery;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.maintenance.UnitFailures;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ForwardingArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.DirtyIndexFeed;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.walk.RebuildPass;
import build.jenesis.repository.walk.WalkProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The change-fed reverse-dependency sweep. Proves the O(&Delta;) steady state over the free
 * {@link DirtyIndexFeed} + {@code PublicationObserver}: a single publish's edges appear in the right shards after an
 * incremental sweep WITHOUT re-parsing any other blob (proven with a blob-open-counting store); an update re-touch is
 * idempotent; a delete removes <em>exactly</em> the deleted blob's contributed edges (via its per-blob record),
 * leaving others intact; the periodic reconcile rebuilds from truth and heals a drift the feed missed (an unmarked
 * import and a hand-corrupted shard), matching the full-rebuild answer; and the observer marks the feed by the blob
 * hash only once the index is built. Mirrors the search module's incremental + reconcile suite.
 */
class DependentsIncrementalTest {

    private static final Duration INTERVAL = Duration.ofHours(1);

    private static final String APP1 = "pkg:maven/com.example/app1@1.0.0";
    private static final String APP2 = "pkg:maven/com.example/app2@1.0.0";
    private static final String SEVENTH = "pkg:maven/com.example/seventh@1.0.0";
    private static final String LOG4J_CORE = "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1";
    private static final String LOG4J_API = "pkg:maven/org.apache.logging.log4j/log4j-api@2.14.1";

    private static final String[] APP1_COORD = {"com.example", "app1", "1.0.0"};
    private static final String[] APP2_COORD = {"com.example", "app2", "1.0.0"};
    private static final String[] CORE = {"org.apache.logging.log4j", "log4j-core", "2.14.1"};
    private static final String[] API = {"org.apache.logging.log4j", "log4j-api", "2.14.1"};

    @TempDir
    Path root;

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("app");
    }

    // ---- the tests ----------------------------------------------------------------------------------------------

    @Test
    void an_add_update_and_delete_are_reflected_after_an_incremental_sweep() throws IOException {
        ArtifactStore store = store();
        publish(store, APP1_COORD, List.<String[]>of(CORE));                 // seeded before the bootstrap sweep
        sweep(store);                                              // bootstrap: full rebuild, index now exists
        assertThat(dependents(store, LOG4J_CORE)).containsExactly(APP1);

        DependentsPublicationObserver observer = new DependentsPublicationObserver();

        // ADD - a second app depending on the same core, marked through the observer, applied incrementally (no full
        // rebuild). Its edge appears in the right shard alongside the first app's.
        String app2 = publish(store, APP2_COORD, List.<String[]>of(CORE));
        observer.onPublished(descriptor(APP2, app2), store);
        sweep(store);
        assertThat(dependents(store, LOG4J_CORE)).containsExactly(APP1, APP2);

        // UPDATE - re-touching the same blob unions its edges idempotently (set semantics): one dependent, no
        // duplicate, exactly as the search index's upsert-by-term.
        observer.onPublished(descriptor(APP2, app2), store);
        sweep(store);
        assertThat(dependents(store, LOG4J_CORE))
                .as("re-touching a blob is idempotent - no duplicate dependent").containsExactly(APP1, APP2);

        // DELETE - onDeleted undoes EXACTLY app2's contributed edges through its per-blob record, leaving app1 (a
        // different blob, indexed by the bootstrap) untouched.
        observer.onDeleted(descriptor(APP2, app2), store);
        sweep(store);
        assertThat(dependents(store, LOG4J_CORE))
                .as("the delete removes exactly the deleted blob's edge; app1's edge survives").containsExactly(APP1);
    }

    @Test
    void a_sweep_after_one_publish_parses_only_the_changed_blob() throws IOException {
        ArtifactStore backing = store();
        for (int n = 0; n < 6; n++) {
            publish(backing, new String[] {"com.example", "base" + n, "1.0.0"}, List.<String[]>of(CORE));
        }
        sweep(backing);                                           // bootstrap: inverts all six (a full rebuild)

        // A seventh blob, depending on a distinct coordinate, marked through the observer. The incremental sweep must
        // parse ONLY it from the blob store - never re-decompress the other six.
        String seventh = publish(backing, new String[] {"com.example", "seventh", "1.0.0"}, List.<String[]>of(API));
        new DependentsPublicationObserver().onPublished(descriptor(SEVENTH, seventh), backing);

        CountingStore counting = new CountingStore(backing);
        sweep(counting);                                          // the O(delta) incremental sweep

        assertThat(counting.opens)
                .as("only the changed blob is opened and re-parsed - O(delta), not O(N) over every blob")
                .containsExactly("blobs/" + seventh);
        // And the delta landed in the right shard.
        assertThat(dependents(backing, LOG4J_API)).as("the seventh blob's edge is recorded").containsExactly(SEVENTH);
        assertThat(dependents(backing, LOG4J_CORE)).as("the six base blobs are still indexed from the bootstrap")
                .hasSize(6);
    }

    @Test
    void the_reconcile_heals_a_drift_the_feed_missed() throws Exception {
        ArtifactStore store = store();
        publish(store, APP1_COORD, List.<String[]>of(CORE));
        sweep(store);                                             // bootstrap

        // Drift 1: a blob imported straight to truth with NO observer mark - the incremental sweep cannot see it.
        publish(store, APP2_COORD, List.<String[]>of(CORE));
        // Drift 2: a shard hand-corrupted with a bogus dependent the feed never produced.
        String ghost = "pkg:maven/com.example/ghost@6.6.6";
        corruptShard(store, LOG4J_CORE, ghost);

        sweep(store);                                            // incremental (default cadence)
        assertThat(dependents(store, LOG4J_CORE))
                .as("the incremental sweep neither sees the unmarked blob nor undoes the injected corruption")
                .contains(ghost).doesNotContain(APP2);

        // The periodic reconcile rebuilds from durable truth: it picks up the drifted blob and drops the bogus edge,
        // matching a full rebuild exactly. Force it with a one-pass cadence.
        sweep(store, Map.of("dependents-reconcile-passes", "1"));
        assertThat(dependents(store, LOG4J_CORE))
                .as("the reconcile rebuilds from truth - the full-rebuild's answer, drift healed")
                .containsExactly(APP1, APP2);
    }

    @Test
    void the_walk_rebuilds_the_index_from_truth_when_a_pass_carrying_the_consumer_completes() throws Exception {
        ArtifactStore store = store();
        publish(store, APP1_COORD, List.<String[]>of(CORE));
        sweep(store);                                             // bootstrap
        publish(store, APP2_COORD, List.<String[]>of(CORE));      // unmarked: the feed never saw it
        String ghost = "pkg:maven/com.example/ghost@6.6.6";
        corruptShard(store, LOG4J_CORE, ghost);
        sweep(store);                                             // incremental, default cadence: never reconciles by count
        assertThat(dependents(store, LOG4J_CORE))
                .as("at the default cadence the sweep never rebuilds from truth on its own")
                .contains(ghost).doesNotContain(APP2);

        RebuildPass.run(WalkProvider.resolve(key -> null).orElseThrow(), store, new Publication(store),
                new RebuildPass.Roots(List.of("publish"), List.of(), List.of(), List.of()),
                List.of(new DependentsRebuildConsumer()));

        assertThat(RebuildPass.failed(store)).as("the rebuild consumer completed").isEmpty();
        assertThat(dependents(store, LOG4J_CORE))
                .as("the walk's completion rebuilt from truth: the drifted blob is in, the bogus edge is gone")
                .containsExactly(APP1, APP2);
    }

    @Test
    void the_safety_valve_forces_a_full_rebuild_that_heals_without_a_mark() throws IOException {
        ArtifactStore store = store();
        publish(store, APP1_COORD, List.<String[]>of(CORE));
        sweep(store);                                             // bootstrap

        publish(store, APP2_COORD, List.<String[]>of(CORE));               // unmarked - the feed never saw it
        sweep(store, Map.of("dependents-incremental", "false"));  // the safety valve: full rebuild every sweep
        assertThat(dependents(store, LOG4J_CORE))
                .as("dependents-incremental=false full-rebuilds from truth, picking up the unmarked blob")
                .containsExactly(APP1, APP2);
    }

    @Test
    void the_observer_marks_the_feed_by_blob_hash_only_after_the_index_is_built() throws IOException {
        ArtifactStore store = store();
        DependentsPublicationObserver observer = new DependentsPublicationObserver();

        // Before any sweep the index is not built - a publish is NOT marked (the bootstrap rebuild covers every blob).
        String early = publish(store, APP1_COORD, List.<String[]>of(CORE));
        observer.onPublished(descriptor(APP1, early), store);
        assertThat(feed(store).pending())
                .as("pre-bootstrap: no marker is left - the full rebuild indexes every blob anyway").isEmpty();

        sweep(store);                                            // bootstrap stamps the built marker

        // After the index exists, a publish IS marked, keyed by the blob hash.
        String later = publish(store, APP2_COORD, List.<String[]>of(CORE));
        observer.onPublished(descriptor(APP2, later), store);
        List<DirtyIndexFeed.Entry> pending = feed(store).pending();
        assertThat(pending).as("one marker for the published blob").hasSize(1);
        assertThat(pending.getFirst().coordinate()).as("the feed is keyed by the blob hash").isEqualTo(later);
        assertThat(pending.getFirst().removed()).as("a publish is a touch, not a removal").isFalse();

        // A descriptor carrying no blob hash contributes no reverse edge, so it marks nothing.
        observer.onPublished(descriptor(APP1, null), store);
        assertThat(feed(store).pending()).as("no blob hash - nothing marked").hasSize(1);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private void sweep(ArtifactStore store) throws IOException {
        sweep(store, Map.of());
    }

    private void sweep(ArtifactStore store, Map<String, String> config) throws IOException {
        new DependentsIndexTask(INTERVAL).repository(context(store, config));
    }

    private static List<String> dependents(ArtifactStore store, String coordinate) throws IOException {
        return new DependentsIndex(store).dependents(coordinate);
    }

    private static DirtyIndexFeed feed(ArtifactStore store) {
        return new DirtyIndexFeed(store, "dependents");
    }

    /** Store a jar carrying a CycloneDX SBOM for {@code root} depending on {@code dependencies}, returning its blob
     *  hash - the key the reverse-dependency graph (and the change feed) is keyed by. */
    private String publish(ArtifactStore store, String[] root, List<String[]> dependencies) throws IOException {
        return store.writeBlob(new ByteArrayInputStream(jar(bom(root, dependencies))));
    }

    private ArtifactDescriptor descriptor(String coordinate, String hash) {
        return new ArtifactDescriptor("maven", coordinate, "1.0.0",
                "/maven/" + coordinate + "/artifact", null, false, hash, 1L);
    }

    /** Hand-corrupt a coordinate's shard by appending a bogus dependent to its line - a drift the change feed never
     *  produced, which only a reconcile from truth can heal (the incremental sweep never touches a shard the feed did
     *  not mark). Writes straight to the same shard object {@code dependents(coordinate)} reads. */
    private void corruptShard(ArtifactStore store, String coordinate, String ghost) throws Exception {
        String key = shardKey(coordinate);
        Optional<ArtifactStore.Versioned> stored = store.readVersioned(key);
        String content = stored.map(v -> new String(v.content(), StandardCharsets.UTF_8)).orElse("");
        String target = enc(coordinate);
        StringBuilder rebuilt = new StringBuilder();
        boolean appended = false;
        for (String line : content.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            if (line.equals(target) || line.startsWith(target + " ")) {
                rebuilt.append(line).append(' ').append(enc(ghost)).append('\n');
                appended = true;
            } else {
                rebuilt.append(line).append('\n');
            }
        }
        if (!appended) {
            rebuilt.append(target).append(' ').append(enc(ghost)).append('\n');
        }
        store.writeVersioned(key, rebuilt.toString().getBytes(StandardCharsets.UTF_8),
                stored.map(ArtifactStore.Versioned::token).orElse(null));
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String shardKey(String coordinate) throws Exception {
        // The shard byte is taken over the NEUTRAL spelling, so a stored purl and the group:name:version a
        // report line keys on land in the same object - mirrored here through the SPI's own neutralise, never a
        // second copy of the mapping.
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(DependentsQuery.neutralise(coordinate).getBytes(StandardCharsets.UTF_8));
        return "dependents/" + HexFormat.of().formatHex(digest, 0, 1);
    }

    /** A minimal CycloneDX 1.6 BOM: {@code root} is the metadata component, each of {@code dependencies} a node in the
     *  resolved tree the index inverts. */
    private static String bom(String[] root, List<String[]> dependencies) {
        StringBuilder components = new StringBuilder();
        for (String[] dependency : dependencies) {
            if (!components.isEmpty()) {
                components.append(",\n");
            }
            components.append(component(dependency));
        }
        return """
                {
                  "bomFormat": "CycloneDX",
                  "specVersion": "1.6",
                  "version": 1,
                  "metadata": { "component": %s },
                  "components": [ %s ],
                  "dependencies": []
                }
                """.formatted(component(root), components);
    }

    private static String component(String[] coordinate) {
        String group = coordinate[0], name = coordinate[1], version = coordinate[2];
        return """
                {
                  "type": "library",
                  "bom-ref": "%s/%s/%s",
                  "group": "%s",
                  "name": "%s",
                  "version": "%s",
                  "purl": "pkg:maven/%s/%s@%s"
                }
                """.formatted(group, name, version, group, name, version, group, name, version);
    }

    private static byte[] jar(String bom) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Sbom-Location", "META-INF/sbom/app.cdx.json");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out, manifest)) {
            jar.putNextEntry(new JarEntry("META-INF/sbom/app.cdx.json"));
            jar.write(bom.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return out.toByteArray();
    }

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
                return config::get;
            }

            @Override
            public Instant now() {
                return Instant.parse("2026-07-03T00:00:00Z");
            }

            @Override
            public void gauge(String name, String description, Map<String, String> tags, double value) {
            }
        };
    }

    /** A blob-open-counting {@link ArtifactStore} decorator: every {@code open} of a {@code blobs/} key is recorded so
     *  a test can prove the incremental sweep re-parses only the changed blob, never the whole set. Everything else
     *  delegates unchanged. */
    private static final class CountingStore extends ForwardingArtifactStore {
        private final List<String> opens = Collections.synchronizedList(new ArrayList<>());

        private CountingStore(ArtifactStore delegate) {
            super(delegate);
        }

        @Override
        public ArtifactStore scope(String segment) {
            return delegate.scope(segment);
        }

        @Override
        public InputStream open(String key) throws IOException {
            if (key.startsWith("blobs/")) {
                opens.add(key);
            }
            return delegate.open(key);
        }
    }
}
