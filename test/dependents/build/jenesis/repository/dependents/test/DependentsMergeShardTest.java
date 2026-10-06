package build.jenesis.repository.dependents.test;

import module java.base;
import module org.junit.jupiter.api;
import java.util.jar.Attributes;
import build.jenesis.repository.dependents.DependentsIndex;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ForwardingArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.walk.ArtifactWalk;
import build.jenesis.repository.walk.WalkProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pass-completion merge (finding: the finisher union). The checkpoint-delta fix removed the quadratic checkpoint
 * write, but the finisher still unioned the whole repository's inverted graph into one in-heap {@code TreeMap} before
 * committing - an OOM on the finishing node once a repository's reverse graph outgrew heap. The merge now runs a shard
 * batch at a time: it re-scans the committed append chunks per batch of shard bytes and commits only that batch's
 * shards, so its peak footprint is a fraction of the graph, never the whole graph. Verified here:
 *
 * <ul>
 *   <li>the merged index still converges to every edge - a coordinate whose edges are spread across many chunks
 *       (the shared {@code common}) collects them all, and each per-app library pins its one app - across shards;</li>
 *   <li>the merge re-scans the committed chunks per shard batch rather than reading them once into a whole-graph
 *       union: with edges spread across the shard space, each committed chunk is read more than once, the observable
 *       signature of the bounded, per-shard-batched finisher.</li>
 * </ul>
 *
 * Walk settings pinned small as in {@code DependentsDeltaCheckpointTest}: a one-item checkpoint stride (one blob per
 * chunk), a one-second claim TTL, and a single segment so one rebuild completes the pass and runs the finisher merge.
 */
class DependentsMergeShardTest {

    private static final String COMMON = "pkg:maven/com.example/common@1.0.0";
    private static final int APPS = 8;

    @TempDir
    Path root;

    @Test
    void the_finisher_merges_per_shard_batch_and_still_converges_to_every_edge() throws IOException {
        ArtifactStore backend = store();
        seedArtifacts(backend);
        ArtifactWalk walk = WalkProvider.resolve(settings(1)::get).orElseThrow();
        ChunkReadCountingStore counting = new ChunkReadCountingStore(backend);

        // A single segment, checkpoint stride 1: every app blob is its own stride and its own append chunk, so the
        // shared coordinate's edges land in as many chunks as there are apps - the cross-chunk union the finisher
        // must reconstruct. One rebuild completes the pass and runs the merge.
        new DependentsIndex(counting, walk).rebuild();

        // Convergence across shards and across chunks: common depends on every app, each lib pins its one app.
        DependentsIndex reader = new DependentsIndex(backend);
        assertThat(reader.dependents(COMMON))
                .as("the shared coordinate's edges, spread across every chunk, are all unioned")
                .containsExactlyElementsOf(apps());
        for (int app = 1; app <= APPS; app++) {
            assertThat(reader.dependents(lib(app))).containsExactly(app(app));
        }

        // The finisher re-scans the committed chunks per shard batch instead of unioning the whole graph in one pass:
        // a whole-graph union reads each chunk exactly once, so reading each chunk more than once is the proof the
        // merge is bounded per shard batch and never holds the whole graph.
        assertThat(counting.distinctChunksRead()).as("the pass committed append chunks the merge read").isPositive();
        assertThat(counting.totalChunkReads())
                .as("chunks are re-scanned per shard batch, so the merge never holds the whole graph at once")
                .isGreaterThan(counting.distinctChunksRead());
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
    }

    private static Map<String, String> settings(int segments) {
        return Map.of(
                "walk.checkpoint", "1",         // commit the cursor after every item: one blob per chunk
                "walk.segments", Integer.toString(segments),
                "walk.ttl", "1");
    }

    private static String app(int index) {
        return "pkg:maven/com.example/app" + index + "@1.0.0";
    }

    private static String lib(int index) {
        return "pkg:maven/com.example/lib" + index + "@1.0.0";
    }

    private static List<String> apps() {
        return IntStream.rangeClosed(1, APPS).mapToObj(DependentsMergeShardTest::app).sorted().toList();
    }

    /** App_i depends on its own lib_i plus the shared common - so the shared coordinate collects an edge from every
     *  app blob (spread across the chunks) and each lib pins exactly one app, and the coordinates spread across the
     *  shard space so the per-shard-batched merge iterates its batches. */
    private static void seedArtifacts(ArtifactStore store) throws IOException {
        for (int app = 1; app <= APPS; app++) {
            String bom = bom(new String[] {"com.example", "app" + app, "1.0.0"},
                    List.of(new String[] {"com.example", "lib" + app, "1.0.0"},
                            new String[] {"com.example", "common", "1.0.0"}));
            store.writeBlob(new ByteArrayInputStream(jar(bom)));
        }
    }

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
            jar.putNextEntry(new JarEntry("com/example/App.class"));
            jar.write(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("META-INF/sbom/app.cdx.json"));
            jar.write(bom.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return out.toByteArray();
    }

    /** Counts reads of the committed append chunks ({@code walks/dependents/state/<nnn>/g<gen>/c<n>}) - distinct keys
     *  and total reads - so a test can tell a per-shard-batched merge (each chunk re-scanned per batch) from a
     *  whole-graph union (each chunk read exactly once). */
    private static final class ChunkReadCountingStore extends ForwardingArtifactStore {
        private static final Pattern CHUNK = Pattern.compile("walks/dependents/state/\\d+/g\\d+/c\\d+");

        private final Set<String> distinct = new HashSet<>();
        private int total;

        private ChunkReadCountingStore(ArtifactStore delegate) {
            super(delegate);
        }

        private int distinctChunksRead() {
            return distinct.size();
        }

        private int totalChunkReads() {
            return total;
        }

        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            if (CHUNK.matcher(key).matches()) {
                distinct.add(key);
                total++;
            }
            return delegate.readVersioned(key);
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return delegate.scope(tenant);
        }
    }
}
