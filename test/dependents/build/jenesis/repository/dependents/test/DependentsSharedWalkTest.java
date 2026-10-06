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
 * The reverse-dependency sweep's ride on the shared artifact walk: a walk-carrying
 * {@link DependentsIndex} enumerates {@code blobs/} as a resumable {@code walks/dependents} pass instead of a
 * private {@code list("blobs")} snapshot, flushing each segment's partial inversion before every cursor commit and
 * committing the final shard set through the pass-completion merge. Verified here: the walk-riding rebuild commits
 * byte-for-byte the shards the buffered recompute writes (across a multi-segment pass, whose per-segment partials
 * the merge unions - and without ever listing the blob namespace); a crash mid-pass resumes from the committed
 * cursor with the flushed partial intact - the pre-cursor blobs are provably never re-opened, yet their edges are
 * in the final index - and converges to the exact shards; and a second in-process walk instance (the stand-in for
 * another VM sharing the store) takes a dead worker's segment over from that same cursor and finishes the pass.
 * Walk settings pinned small as in the other shared-walk suites: one-item checkpoint stride, a one-second claim
 * TTL, and one segment where the crash position must be deterministic.
 */
class DependentsSharedWalkTest {

    private static final String COMMON = "pkg:maven/com.example/common@1.0.0";
    private static final int APPS = 6;

    /** How many times {@code awaitRebuild} re-drives the rebuild before giving up. The bound is attempts, never a
     *  reading of the wall clock: a loaded machine must make the wait longer, not weaker. A clock-bounded wait that
     *  expires hands the cells an <em>empty</em> recording, and "no blob before the cursor was re-opened" is true of
     *  a pass that never ran at all - the resume-versus-restart claim would then pass saying nothing. */
    private static final int PASSES = 300;

    @TempDir
    Path root;

    @Test
    void the_walk_riding_rebuild_commits_exactly_the_buffered_recomputes_shards_across_a_multi_segment_pass()
            throws IOException {
        ArtifactStore backend = store();
        seedArtifacts(backend);
        // Four segments over six blobs: a coordinate's shard collects edges from blobs in several segments, so
        // every shard below must come from the pass-completion merge of the segments' recorded partials.
        ArtifactWalk walk = WalkProvider.resolve(settings(4)::get).orElseThrow();
        int bufferedShards = new DependentsIndex(backend).rebuild();
        Map<String, byte[]> buffered = shards(backend);
        for (String name : backend.list("dependents")) {
            backend.delete("dependents/" + name);    // the walk-riding pass must commit every shard itself
        }

        int shards = new DependentsIndex(new ListRefusingStore(backend), walk).rebuild();

        assertThat(shards).as("the merged pass commits exactly as many shards as the buffered recompute")
                .isEqualTo(bufferedShards);
        Map<String, byte[]> committed = shards(backend);
        assertThat(committed.keySet()).isEqualTo(buffered.keySet());
        for (Map.Entry<String, byte[]> shard : buffered.entrySet()) {
            assertThat(committed.get(shard.getKey()))
                    .as("shard %s is byte-for-byte the buffered recompute's", shard.getKey())
                    .isEqualTo(shard.getValue());
        }
        assertThat(backend.readVersioned("walks/dependents/manifest"))
                .as("the enumeration rode the shared walk's pass, not a private blob listing").isPresent();
        assertThat(new DependentsIndex(backend).built()).isTrue();
    }

    @Test
    void a_mid_pass_crash_resumes_from_the_cursor_with_its_partial_inversion_and_converges() throws IOException {
        ArtifactStore backend = store();
        seedArtifacts(backend);
        ArtifactWalk walk = WalkProvider.resolve(settings(1)::get).orElseThrow();
        // By the third blob open the first two are inverted, cursor-committed and their partial durably flushed
        // (checkpoint stride 1), so the crash costs at most that one blob's parse.
        CrashingStore crashing = new CrashingStore(backend, 3);

        assertThatThrownBy(() -> new DependentsIndex(crashing, walk).rebuild())
                .as("the third blob's open crashes the worker").isInstanceOf(IllegalStateException.class);
        List<String> committed = crashing.opened.subList(0, 2);

        RecordingStore recording = new RecordingStore(backend);
        awaitRebuild(new DependentsIndex(recording, walk));

        for (String key : committed) {
            assertThat(recording.opened.getOrDefault(key, 0))
                    .as("a blob before the committed cursor is not re-opened - a resume, not a restart")
                    .isZero();
        }
        DependentsIndex reader = new DependentsIndex(backend);
        assertThat(reader.dependents(COMMON))
                .as("the pre-crash blobs' edges survived in the flushed partial - every app is indexed")
                .containsExactlyElementsOf(apps());
        for (int app = 1; app <= APPS; app++) {
            assertThat(reader.dependents(lib(app))).containsExactly(app(app));
        }
    }

    @Test
    void a_second_walk_instance_takes_over_a_dead_workers_segment_from_its_cursor() throws IOException {
        ArtifactStore backend = store();
        seedArtifacts(backend);
        // Two walk instances over one store: each resolve carries its own node identity, so this is the in-process
        // stand-in for two VMs sharing the store - claim, cursor, partials and manifest travel through the store.
        ArtifactWalk nodeA = WalkProvider.resolve(settings(1)::get).orElseThrow();
        ArtifactWalk nodeB = WalkProvider.resolve(settings(1)::get).orElseThrow();
        CrashingStore crashing = new CrashingStore(backend, 3);

        assertThatThrownBy(() -> new DependentsIndex(crashing, nodeA).rebuild())
                .as("node A dies mid-segment").isInstanceOf(IllegalStateException.class);
        List<String> committed = crashing.opened.subList(0, 2);

        RecordingStore recording = new RecordingStore(backend);
        awaitRebuild(new DependentsIndex(recording, nodeB));

        for (String key : committed) {
            assertThat(recording.opened.getOrDefault(key, 0))
                    .as("node B resumed from node A's cursor and adopted its flushed partial").isZero();
        }
        assertThat(new DependentsIndex(backend).dependents(COMMON))
                .as("node B finished the dead node's pass and merged the full index")
                .containsExactlyElementsOf(apps());
    }

    @Test
    void a_completed_and_merged_pass_is_not_re_merged_on_the_next_rebuild() throws IOException {
        ArtifactStore backend = store();
        seedArtifacts(backend);
        ArtifactWalk walk = WalkProvider.resolve(settings(1)::get).orElseThrow();

        new DependentsIndex(backend, walk).rebuild();               // the pass completes and merges once
        assertThat(backend.readVersioned("walks/dependents/merged"))
                .as("the completed pass records its merged generation").isPresent();

        // A second rebuild must NOT re-merge the already-merged previous pass (the pre-walk merge is the double
        // merge/commit I/O the marker exists to prevent): only the fresh pass this call runs may commit. Every commit
        // writes the built marker, so a re-merge is a second built write - without the marker guard this counts 2.
        BuiltCountingStore counting = new BuiltCountingStore(backend);
        new DependentsIndex(counting, walk).rebuild();

        assertThat(counting.builtWrites)
                .as("the merged previous pass is skipped - one commit for the fresh pass, not two").isEqualTo(1);
        assertThat(new DependentsIndex(backend).dependents(COMMON))
                .as("convergence is unchanged").containsExactlyElementsOf(apps());
    }

    @Test
    void the_walk_less_rebuild_is_shard_batched_yet_indexes_every_edge_across_shard_bands() throws IOException {
        ArtifactStore backend = store();
        seedArtifacts(backend);
        // The single-node walk-less rebuild is shard-batched - it inverts one fixed-width band of shard bytes at a
        // time so the whole repository's reverse graph is never held in heap - but the band filter partitions the 256
        // shard bytes, so an edge whose dependency coordinate lands anywhere in any band must still be recorded. Prove
        // it end-to-end: read every dependent back through the query path after a rebuild with no walk installed.
        int shards = new DependentsIndex(backend).rebuild();
        assertThat(shards).as("the walk-less rebuild committed shards").isPositive();

        DependentsIndex reader = new DependentsIndex(backend);
        assertThat(reader.dependents(COMMON))
                .as("the shared coordinate collects an edge from every app blob - none dropped at a band boundary")
                .containsExactlyElementsOf(apps());
        for (int app = 1; app <= APPS; app++) {
            assertThat(reader.dependents(lib(app)))
                    .as("lib%d's sole dependent survives the band-filtered rebuild", app).containsExactly(app(app));
        }
        assertThat(reader.built()).as("a shard-batched rebuild still stamps the built marker").isTrue();
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
    }

    private static Map<String, String> settings(int segments) {
        return Map.of(
                "walk.checkpoint", "1",         // commit the cursor after every item: a crash re-visits <= 1
                "walk.segments", Integer.toString(segments),
                "walk.ttl", "1");               // a crashed claim expires within the test's patience
    }

    private static String app(int index) {
        return "pkg:maven/com.example/app" + index + "@1.0.0";
    }

    private static String lib(int index) {
        return "pkg:maven/com.example/lib" + index + "@1.0.0";
    }

    /** Every app's purl, sorted - what {@code dependents(COMMON)} must return however the pass was cut or crashed. */
    private static List<String> apps() {
        return IntStream.rangeClosed(1, APPS).mapToObj(DependentsSharedWalkTest::app).sorted().toList();
    }

    /** Six SBOM-carrying jars, app<i>i</i> depending on its own lib<i>i</i> plus the shared common - so the shared
     *  coordinate's shard needs an edge from every blob (across all segments) and each lib pins one exact blob. */
    private static void seedArtifacts(ArtifactStore store) throws IOException {
        for (int app = 1; app <= APPS; app++) {
            String bom = bom(new String[] {"com.example", "app" + app, "1.0.0"},
                    List.of(new String[] {"com.example", "lib" + app, "1.0.0"},
                            new String[] {"com.example", "common", "1.0.0"}));
            store.writeBlob(new ByteArrayInputStream(jar(bom)));
        }
    }

    /** Every committed shard object (the built marker excluded) as name-to-bytes, comparable wholesale. */
    private static Map<String, byte[]> shards(ArtifactStore store) throws IOException {
        Map<String, byte[]> shards = new TreeMap<>();
        for (String name : store.list("dependents")) {
            if (name.length() != 2) {
                continue;
            }
            store.readVersioned("dependents/" + name)
                    .ifPresent(versioned -> shards.put(name, versioned.content()));
        }
        return shards;
    }

    /** Re-run the rebuild until the crashed worker's claim expired and a pass completed with the full index; a run
     *  while the claim is still live claims nothing and reports the (absent) shards - refused, never stolen. Bounded
     *  by rebuilds attempted, and loud when they run out: the cells below tell a resume from a restart by the blobs
     *  the recording store was asked to open, and a wait that gave up quietly would hand them an empty recording -
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

        @Override
        public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
            delegate.page(prefix, startAfter, limit, consumer);   // the backend's native paging, never list()
        }

        @Override
        public void pageListed(String prefix, String startAfter, int limit, Consumer<Listed> consumer) {
            delegate.pageListed(prefix, startAfter, limit, consumer);   // the primitive the walk pages through; page derives from it
        }

    }

    /** Fails a {@code list} of the blob namespace outright - the walk pages, so a walk-riding rebuild that never
     *  snapshots {@code list("blobs")} passes; the buffered recompute would fail loudly here. */
    private static final class ListRefusingStore extends ForwardingStore {

        private ListRefusingStore(ArtifactStore delegate) {
            super(delegate);
        }

        @Override
        public List<String> list(String prefix) {
            if (prefix.equals("blobs")) {
                throw new IllegalStateException("the walk-riding rebuild must never list the blob namespace");
            }
            return super.list(prefix);
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return delegate.scan(prefix, startAfter, limit, consumer);
    }
}

    /** Fails the n-th blob {@code open} with an unchecked throw - the injected crash of the inverting worker
     *  mid-blob. Unchecked deliberately: the sweep skips a blob whose read fails with {@link IOException} (a bad
     *  blob never derails a pass), so a process-death stand-in must not look like one bad blob. Every other call
     *  passes through, so the walk's own state commits normally; the keys opened before the crash are recorded. */
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

    /** Counts writes of the {@code dependents/built} marker - a commit writes it exactly once, so the count is the
     *  number of merges that reached commit in a rebuild: proof that an already-merged pass is not re-merged. */
    private static final class BuiltCountingStore extends ForwardingStore {

        private int builtWrites;

        private BuiltCountingStore(ArtifactStore delegate) {
            super(delegate);
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            if (key.equals("dependents/built")) {
                builtWrites++;
            }
            return super.writeVersioned(key, content, expected);
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return delegate.scan(prefix, startAfter, limit, consumer);
    }
}

    /** Counts blob {@code open} calls per key - the sweep opens a visited blob exactly once, so a zero count is
     *  proof the blob was never re-visited. */
    private static final class RecordingStore extends ForwardingStore {

        private final Map<String, Integer> opened = new HashMap<>();

        @Override
        public InputStream open(String key) throws IOException {
            if (key.startsWith("blobs/")) {
                opened.merge(key, 1, Integer::sum);
            }
            return super.open(key);
        }

        private RecordingStore(ArtifactStore delegate) {
            super(delegate);
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return delegate.scan(prefix, startAfter, limit, consumer);
    }
}
}
