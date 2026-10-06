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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The delta-based checkpoint persistence: {@link DependentsIndex}'s inverting visitor does not re-serialise the whole
 * accumulated segment partial on every checkpoint stride, which would be O(strides x segment-size) write amplification
 * on a long segment. Each segment keeps a small CAS-fenced HEAD ({@code walks/dependents/state/<nnn>/head} -
 * generation, holder, cursor, chunk count) plus a sequence of immutable append chunks
 * ({@code walks/dependents/state/<nnn>/g<gen>/c<n>}), and each {@code beforeCheckpoint} writes only the edges new
 * since the last checkpoint as the next chunk. Verified here:
 *
 * <ul>
 *   <li>(a) each stride's chunk is bounded by that stride's new edges, not the accumulated total - the chunk sizes
 *       stay flat across a long segment where the old whole-partial flush grew linearly with the chunk index;</li>
 *   <li>(b) a mid-segment crash + takeover reconstructs the full partial by unioning the committed chunks and
 *       converges to the exact index - the pre-crash chunks (written by the dead worker) survive and the successor
 *       continues appending from the HEAD's chunk count (mirrors {@code DependentsSharedWalkTest}'s crash idiom);</li>
 *   <li>(c) a straight-through multi-checkpoint pass still converges to every edge.</li>
 * </ul>
 *
 * Walk settings pinned small as in {@code DependentsSharedWalkTest}: one-item checkpoint stride (so a stride is one
 * blob, and a chunk is one blob's edges), a one-second claim TTL, and a single segment so the crash position and the
 * chunk sequence are deterministic.
 */
class DependentsDeltaCheckpointTest {

    private static final String COMMON = "pkg:maven/com.example/common@1.0.0";
    private static final int APPS = 8;

    /** How many times {@code awaitRebuild} re-drives the rebuild before giving up. The bound is attempts, never a
     *  reading of the wall clock: a loaded machine must make the wait longer, not weaker. A clock-bounded wait that
     *  expires hands the cells an <em>empty</em> recording, and "no blob before the cursor was re-opened" is true of
     *  a pass that never ran at all - the resume-versus-restart claim would then pass saying nothing. */
    private static final int PASSES = 300;

    @TempDir
    Path root;

    @Test
    void each_stride_writes_only_its_new_edges_as_a_chunk_bounded_not_accumulated() throws IOException {
        ArtifactStore backend = store();
        seedArtifacts(backend);
        ArtifactWalk walk = WalkProvider.resolve(settings(1)::get).orElseThrow();
        ChunkCountingStore counting = new ChunkCountingStore(backend);

        new DependentsIndex(counting, walk).rebuild();

        // One segment, checkpoint stride 1: every SBOM blob is its own stride, so the pass writes exactly one chunk
        // per blob (c0..c<APPS-1>). Each blob contributes the same shape (app_i depends on lib_i and common), so
        // every chunk holds one stride's two edges - never the running union the whole-partial flush re-wrote.
        List<Integer> sizes = counting.chunkSizes();
        assertThat(sizes).as("one append chunk per stride, over the whole segment").hasSize(APPS);
        int min = Collections.min(sizes), max = Collections.max(sizes);
        assertThat(max - min)
                .as("every chunk is one stride's delta (~two edges), so the sizes stay flat - "
                        + "the old whole-partial flush would grow the last chunk to ~APPS x the first")
                .isLessThan(min);
        assertThat(counting.totalChunkBytes())
                .as("total persisted bytes are linear in the edge count (O(segment)), not O(strides x segment)")
                .isLessThan(min * APPS * 2);

        // And the delta chunks still reconstruct the exact index.
        assertThat(new DependentsIndex(backend).dependents(COMMON)).containsExactlyElementsOf(apps());
        for (int app = 1; app <= APPS; app++) {
            assertThat(new DependentsIndex(backend).dependents(lib(app))).containsExactly(app(app));
        }
    }

    @Test
    void a_mid_segment_crash_reconstructs_the_full_partial_from_the_committed_chunks_and_converges()
            throws IOException {
        ArtifactStore backend = store();
        seedArtifacts(backend);
        // Two walk instances over one store, the in-process stand-in for two VMs: node A crashes mid-segment, node B
        // takes the segment over from the committed cursor - claim, HEAD, chunks and manifest all travel the store.
        ArtifactWalk nodeA = WalkProvider.resolve(settings(1)::get).orElseThrow();
        ArtifactWalk nodeB = WalkProvider.resolve(settings(1)::get).orElseThrow();
        CrashingStore crashing = new CrashingStore(backend, 3);

        assertThatThrownBy(() -> new DependentsIndex(crashing, nodeA).rebuild())
                .as("node A dies opening the third blob").isInstanceOf(IllegalStateException.class);
        List<String> committed = crashing.opened.subList(0, 2);

        // By the third open the first two blobs are inverted, cursor-committed and each flushed as its own chunk
        // (stride 1): the HEAD carries chunkCount 2 and the two chunks c0, c1 are durably present - the partial the
        // successor rebuilds the segment from, not a re-walk of the whole range.
        assertThat(chunkNames(backend, 0)).as("the two committed strides each left an append chunk").hasSize(2);
        assertThat(headChunkCount(backend, 0)).as("the HEAD's chunk count fences the merge at the committed chunks")
                .isEqualTo(2);

        RecordingStore recording = new RecordingStore(backend);
        awaitRebuild(new DependentsIndex(recording, nodeB));

        for (String key : committed) {
            assertThat(recording.opened.getOrDefault(key, 0))
                    .as("node B resumed from node A's cursor and adopted its committed chunks, not a restart").isZero();
        }
        DependentsIndex reader = new DependentsIndex(backend);
        assertThat(reader.dependents(COMMON))
                .as("the dead worker's chunks were unioned with the successor's - every app is indexed")
                .containsExactlyElementsOf(apps());
        for (int app = 1; app <= APPS; app++) {
            assertThat(reader.dependents(lib(app))).containsExactly(app(app));
        }
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
    }

    private static Map<String, String> settings(int segments) {
        return Map.of(
                "walk.checkpoint", "1",         // commit the cursor after every item: one blob per stride
                "walk.segments", Integer.toString(segments),
                "walk.ttl", "1");               // a crashed claim expires within the test's patience
    }

    private static String app(int index) {
        return "pkg:maven/com.example/app" + index + "@1.0.0";
    }

    private static String lib(int index) {
        return "pkg:maven/com.example/lib" + index + "@1.0.0";
    }

    private static List<String> apps() {
        return IntStream.rangeClosed(1, APPS).mapToObj(DependentsDeltaCheckpointTest::app).sorted().toList();
    }

    /** Every committed append chunk's child name for segment {@code index} ({@code c0}, {@code c1}, ...), sorted. */
    private static List<String> chunkNames(ArtifactStore store, int index) {
        String base = "walks/dependents/state/" + String.format("%03d", index);
        for (String entry : store.list(base)) {
            if (entry.startsWith("g")) {                // the sole generation directory for this pass
                return store.list(base + "/" + entry);
            }
        }
        return List.of();
    }

    /** The chunk count the segment's HEAD records - the merge's authority for how many chunks are committed. */
    private static int headChunkCount(ArtifactStore store, int index) throws IOException {
        String head = "walks/dependents/state/" + String.format("%03d", index) + "/head";
        return store.readVersioned(head)
                .map(versioned -> Integer.parseInt(
                        new String(versioned.content(), StandardCharsets.UTF_8).strip().split(" ")[2]))
                .orElseThrow();
    }

    /** Six-plus SBOM-carrying jars, app_i depending on its own lib_i plus the shared common - so the shared
     *  coordinate's shard collects an edge from every blob and each lib pins one exact blob, and every stride's
     *  delta is the same fixed two edges, making a growing chunk a visible regression. */
    private static void seedArtifacts(ArtifactStore store) throws IOException {
        for (int app = 1; app <= APPS; app++) {
            String bom = bom(new String[] {"com.example", "app" + app, "1.0.0"},
                    List.of(new String[] {"com.example", "lib" + app, "1.0.0"},
                            new String[] {"com.example", "common", "1.0.0"}));
            store.writeBlob(new ByteArrayInputStream(jar(bom)));
        }
    }

    /** Re-run the rebuild until the crashed worker's claim expired and a pass completed with the full index. Bounded
     *  by rebuilds attempted, and loud when they run out: the cell below tells a resume from a restart by the blobs
     *  the recording store was asked to open, and a wait that gave up quietly would hand it an empty recording -
     *  "no blob before the cursor was re-opened" is true of a pass that never ran at all. */
    private static void awaitRebuild(DependentsIndex index) throws IOException {
        for (int pass = 0; pass < PASSES; pass++) {
            index.rebuild();
            if (index.dependents(COMMON).size() == APPS) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted awaiting the claim expiry", e);
            }
        }
        throw new AssertionError("No rebuild pass inverted all " + APPS + " dependents of " + COMMON
                + " across " + PASSES + " passes");
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

    /** Delegates everything to the backend; subclasses observe or fail single calls. */
    private static abstract class ForwardingStore extends ForwardingArtifactStore {

        ForwardingStore(ArtifactStore delegate) {
            super(delegate);
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return delegate.scope(tenant);
        }

    }

    /** Records the byte length of every append-chunk write ({@code walks/dependents/state/<nnn>/g<gen>/c<n>}), in
     *  write order - so a test can assert each stride's chunk is bounded by that stride's delta, not the running
     *  total the old whole-partial flush re-wrote. */
    private static final class ChunkCountingStore extends ForwardingStore {

        private static final Pattern CHUNK =
                Pattern.compile("walks/dependents/state/\\d+/g\\d+/c\\d+");

        private final List<Integer> chunkSizes = new ArrayList<>();

        private ChunkCountingStore(ArtifactStore delegate) {
            super(delegate);
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            boolean written = super.writeVersioned(key, content, expected);
            if (written && CHUNK.matcher(key).matches()) {
                chunkSizes.add(content.length);
            }
            return written;
        }

        private List<Integer> chunkSizes() {
            return chunkSizes;
        }

        private int totalChunkBytes() {
            return chunkSizes.stream().mapToInt(Integer::intValue).sum();
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return delegate.scan(prefix, startAfter, limit, consumer);
    }
}

    /** Fails the n-th blob {@code open} with an unchecked throw - the injected process-death of the inverting worker
     *  mid-blob (unchecked so it is not mistaken for one bad blob the sweep would skip). Keys opened are recorded. */
    private static final class CrashingStore extends ForwardingStore {

        private final List<String> opened = new ArrayList<>();
        private final int crashOnNth;

        private CrashingStore(ArtifactStore delegate, int crashOnNth) {
            super(delegate);
            this.crashOnNth = crashOnNth;
        }

        @Override
        public InputStream open(String key) throws IOException {
            if (key.startsWith("blobs/")) {
                opened.add(key);
                if (opened.size() == crashOnNth) {
                    throw new IllegalStateException("injected crash on " + key);
                }
            }
            return super.open(key);
        }
    }

    /** Counts blob {@code open} calls per key - a zero count proves the blob was never re-visited on resume. */
    private static final class RecordingStore extends ForwardingStore {

        private final Map<String, Integer> opened = new HashMap<>();

        private RecordingStore(ArtifactStore delegate) {
            super(delegate);
        }

        @Override
        public InputStream open(String key) throws IOException {
            if (key.startsWith("blobs/")) {
                opened.merge(key, 1, Integer::sum);
            }
            return super.open(key);
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return delegate.scan(prefix, startAfter, limit, consumer);
    }
}
}
