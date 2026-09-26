package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static build.jenesis.repository.gateway.testkit.FormatDrive.Call;
import static build.jenesis.repository.gateway.testkit.FormatDrive.format;

/**
 * The denial-of-service fix for {@code HuggingFaceFormat.resolveRevision}: it is called from the hot single-file
 * download ({@code file()}) on EVERY read, and its only question of a revision is "does it hold any file?". The former
 * {@code store.list(base + "/revs/<rev>/files").isEmpty()} materialised the revision's ENTIRE (attacker-publishable)
 * file listing just to test emptiness, so a revision with many thousands of files heap-blew on each download. The fix
 * answers the same question with a bounded ONE-child probe ({@code store.page(prefix, "", 1, ...)}).
 *
 * <p>Drives the real {@code HuggingFaceFormat} over a spy store that DELEGATES everything to a filesystem store (whose
 * {@code page} is a native bounded scan - a {@code MemStore}'s default {@code page} would re-list, so the proof needs a
 * real backend) but <b>throws</b> if a single-file download {@code list()}s a revision's {@code files} prefix, and
 * <b>records</b> every {@code page()} of one. A revision is seeded with many files, then:
 * <ol>
 *   <li>a single-file download succeeds while NO {@code list(.../files)} is made, and the existence probe fetches at
 *       most ONE child ({@code page} limit 1) - the whole listing is never materialised;</li>
 *   <li>an unpopulated revision still 404s (the empty-revision-&gt;404 contract is preserved).</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class HuggingFaceResolveRevisionProbeTest {

    @TempDir
    static Path root;

    private static final String REPO_ID = "bert-base-uncased";
    private static final int FILE_COUNT = 64;   // a "many files" revision the old isEmpty() would have listed whole

    private ProbeStore store;
    private final RepositoryFormat format = format("huggingface");

    @BeforeAll
    public void seed() throws IOException {
        System.setProperty("jenreg.filesystem.root", root.toString());
        ArtifactStore filesystem = ArtifactStoreProvider.resolve("filesystem", System::getProperty)
                .scope("default").scope("myrepo");
        store = new ProbeStore(filesystem);

        // Publish many files to main through the format's own PUT path (this legitimately walks the file tree once per
        // upload to stamp the branch commit, so file-listing is allowed during the seed - forbidding starts after).
        for (int i = 0; i < FILE_COUNT; i++) {
            byte[] body = ("weights-shard-" + i).getBytes(StandardCharsets.UTF_8);
            Call put = new Call("PUT", resolve("main", "file" + i + ".bin"), body);
            format.handle(put, store);
            assertThat(put.status).as("file " + i + " publishes").isEqualTo(201);
        }
        store.forbidFileList = true;   // from here a single-file download must never list a revision's files prefix
    }

    @AfterAll
    public void teardown() {
        System.clearProperty("jenreg.filesystem.root");
    }

    @Test
    public void a_single_file_download_probes_existence_by_paging_one_child() throws IOException {
        store.filePageCount = 0;
        store.maxFilePageLimit = 0;

        Call get = new Call("GET", resolve("main", "file0.bin"));
        format.handle(get, store);   // ProbeStore throws if this list()s the revision's whole files prefix

        assertThat(get.status).as("the file downloads").isEqualTo(200);
        assertThat(new String(get.body(), StandardCharsets.UTF_8)).isEqualTo("weights-shard-0");
        assertThat(store.filePageCount)
                .as("resolveRevision's existence check PAGES the files prefix")
                .isPositive();
        assertThat(store.maxFilePageLimit)
                .as("it fetches AT MOST ONE child - never materialising the whole (many-file) listing")
                .isEqualTo(1);
    }

    @Test
    public void an_unpopulated_revision_is_still_404() throws IOException {
        Call get = new Call("GET", resolve("does-not-exist", "file0.bin"));
        format.handle(get, store);
        assertThat(get.status)
                .as("an empty revision resolves to null -> 404 (resolution semantics preserved)")
                .isEqualTo(404);
    }

    private static String resolve(String revision, String path) {
        return "/huggingface/hf/" + REPO_ID + "/resolve/" + revision + "/" + path;
    }

    /**
     * A delegating {@link ArtifactStore} that, once {@link #forbidFileList} is set, THROWS if a revision's whole
     * {@code .../files} listing is materialised via {@code list()}, and always COUNTS {@code page()} of one (recording
     * the largest limit seen), so the test proves the single-file download's existence check pages one child rather
     * than listing the whole revision. Every other operation delegates untouched.
     */
    private static final class ProbeStore implements ArtifactStore {
        @Override
        public Object identity() {
            return delegate.identity();   // a decorator answers its delegate's subspace
        }

        private final ArtifactStore delegate;
        boolean forbidFileList;
        int filePageCount;
        int maxFilePageLimit;

        private ProbeStore(ArtifactStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<String> list(String prefix) {
            if (forbidFileList && prefix.endsWith("/files")) {
                throw new AssertionError("resolveRevision must not materialise a revision's whole file listing to "
                        + "test existence - it must page one child (the denial-of-service fix): list(\"" + prefix + "\")");
            }
            return delegate.list(prefix);
        }

        @Override
        public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
            if (prefix.endsWith("/files")) {
                filePageCount++;
                maxFilePageLimit = Math.max(maxFilePageLimit, limit);
            }
            delegate.page(prefix, startAfter, limit, consumer);
        }

        @Override public ArtifactStore scope(String tenant) { return delegate.scope(tenant); }
        @Override public boolean exists(String key) { return delegate.exists(key); }
        @Override public void read(String key, OutputStream out) throws IOException { delegate.read(key, out); }
        @Override public InputStream open(String key) throws IOException { return delegate.open(key); }
        @Override public void write(String key, InputStream in) throws IOException { delegate.write(key, in); }
        @Override public String writeBlob(InputStream in) throws IOException { return delegate.writeBlob(in); }
        @Override public long size(String key) throws IOException { return delegate.size(key); }
        @Override public void delete(String key) throws IOException { delegate.delete(key); }
        @Override public Optional<Versioned> readVersioned(String key) throws IOException {
            return delegate.readVersioned(key);
        }
        @Override public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            return delegate.writeVersioned(key, content, expected);
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return delegate.scan(prefix, startAfter, limit, consumer);
    }
}
}
