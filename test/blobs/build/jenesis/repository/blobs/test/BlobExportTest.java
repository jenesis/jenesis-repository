package build.jenesis.repository.blobs.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.blobs.BlobExport;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.RepositoryExporter.Exported;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Withheld;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The export of a format that keeps its files in its own blobs namespace: each pointer's file is put at its path under
 * the target, in the order the format gives, with its stored bytes - and a withheld one is never sent.
 */
class BlobExportTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private Blobs blobs;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        blobs = new Blobs(store);
    }

    @Test
    void each_file_goes_at_its_key_less_the_mount_in_the_order_given() throws IOException {
        blobs.write("go/example.com/hello/@v/v1.0.0.info", bytes("{}"));
        blobs.write("go/example.com/hello/@v/v1.0.0.mod", bytes("module example.com/hello"));
        blobs.write("go/example.com/hello/@v/v1.0.0.zip", bytes("zip"));
        Recording target = new Recording();

        assertThat(BlobExport.put(store, "/go", List.of("go/example.com/hello/@v/v1.0.0.info",
                "go/example.com/hello/@v/v1.0.0.mod", "go/example.com/hello/@v/v1.0.0.zip"), target))
                .isEqualTo(Exported.PUBLISHED);
        assertThat(target.sent.keySet()).containsExactly("example.com/hello/@v/v1.0.0.info",
                "example.com/hello/@v/v1.0.0.mod", "example.com/hello/@v/v1.0.0.zip");
        assertThat(target.sent.get("example.com/hello/@v/v1.0.0.mod")).isEqualTo("module example.com/hello");
    }

    @Test
    void a_pair_puts_a_file_at_a_path_other_than_its_key() throws IOException {
        blobs.write("conda/main/linux-64/pkgs/demo-1.0-0.conda", bytes("package"));
        Recording target = new Recording();

        BlobExport.put(store, List.of(new BlobExport.Pair("conda/main/linux-64/pkgs/demo-1.0-0.conda",
                "main/linux-64/demo-1.0-0.conda")), target);
        assertThat(target.sent).containsExactly(Map.entry("main/linux-64/demo-1.0-0.conda", "package"));
    }

    @Test
    void a_withheld_file_is_never_sent_and_a_version_with_nothing_else_is_withheld() throws IOException {
        blobs.write("homebrew/main/demo-1.0.arm64_sonoma.bottle.tar.gz", bytes("bottle"));
        Withheld.mark(store, blobs.hash("homebrew/main/demo-1.0.arm64_sonoma.bottle.tar.gz").orElseThrow());
        Recording target = new Recording();

        assertThat(BlobExport.put(store, "/homebrew", List.of("homebrew/main/demo-1.0.arm64_sonoma.bottle.tar.gz"),
                target)).isEqualTo(Exported.WITHHELD);
        assertThat(target.sent).isEmpty();
    }

    @Test
    void a_file_already_there_is_not_sent_again() throws IOException {
        blobs.write("rpm/main/demo-1.0-1.x86_64.rpm", bytes("rpm"));
        Recording target = new Recording();
        BlobExport.put(store, "/rpm", List.of("rpm/main/demo-1.0-1.x86_64.rpm"), target);
        target.sent.clear();

        assertThat(BlobExport.put(store, "/rpm", List.of("rpm/main/demo-1.0-1.x86_64.rpm"), target))
                .isEqualTo(Exported.ALREADY_PRESENT);
        assertThat(target.sent).isEmpty();
    }

    @Test
    void a_file_that_cannot_be_asked_for_goes_first_and_only_while_something_else_is_missing() throws IOException {
        blobs.write("winget/main/manifest/Acme.Demo/1.0", bytes("{\"PackageVersion\":\"1.0\"}"));
        blobs.write("winget/main/blob/Acme.Demo/1.0/demo.exe", bytes("installer"));
        List<BlobExport.Pair> pairs = List.of(
                new BlobExport.Pair("winget/main/manifest/Acme.Demo/1.0", "main/manifests/Acme.Demo/1.0",
                        Optional.empty()),
                new BlobExport.Pair("winget/main/blob/Acme.Demo/1.0/demo.exe", "main/installers/Acme.Demo/1.0/demo.exe"));
        Recording target = new Recording();

        assertThat(BlobExport.put(store, pairs, target)).isEqualTo(Exported.PUBLISHED);
        assertThat(target.sent.keySet()).containsExactly("main/manifests/Acme.Demo/1.0",
                "main/installers/Acme.Demo/1.0/demo.exe");
        target.sent.clear();

        assertThat(BlobExport.put(store, pairs, target)).as("every installer is there, so the version is")
                .isEqualTo(Exported.ALREADY_PRESENT);
        assertThat(target.sent).isEmpty();
    }

    @Test
    void a_key_outside_the_mount_is_refused_rather_than_put_somewhere_else() {
        assertThatThrownBy(() -> BlobExport.put(store, "/apk", List.of("rpm/main/x.rpm"), new Recording()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** A target that keeps what it was sent, by path, and serves it back by hash. */
    private static final class Recording implements ExportTarget {

        final Map<String, String> sent = new LinkedHashMap<>();
        private final Map<String, String> held = new HashMap<>();

        @Override
        public Response send(Request request) throws IOException {
            try (InputStream in = request.body().open()) {
                byte[] content = in.readAllBytes();
                sent.put(request.path(), new String(content, StandardCharsets.UTF_8));
                held.put(request.path(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
            return new Response(201, "");
        }

        @Override
        public Optional<String> sha256(String path) {
            return Optional.ofNullable(held.get(path));
        }

        @Override
        public Optional<Credential> credential() {
            return Optional.empty();
        }
    }
}
