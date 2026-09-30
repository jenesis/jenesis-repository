package build.jenesis.repository.format.oci.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.format.oci.OciFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Withheld;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-repository blob mount: {@code POST /v2/<name>/blobs/uploads/?mount=<digest>&from=<other>} links a blob the
 * caller may read in another repository without an upload - {@code 201} with the blob's location - and otherwise
 * opens the ordinary upload session, {@code 202}. The fallback is one answer whatever refused the mount, so a caller
 * that may not read {@code from} learns nothing of what it holds. Whether the caller may read it is the edge's to say
 * ({@code FormatExchange.readable}); here it is answered by the test as an edge would.
 */
class OciMountTest {

    @TempDir
    Path root;

    private ArtifactStore target;
    private ArtifactStore source;
    private final OciFormat format = new OciFormat();

    @BeforeEach
    void setUp() {
        ArtifactStore tenant = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("acme");
        target = tenant.scope("images");
        source = tenant.scope("base");
    }

    @Test
    void a_blob_the_caller_may_read_elsewhere_is_linked_without_an_upload() throws IOException {
        byte[] layer = "a shared base layer".getBytes(StandardCharsets.UTF_8);
        String hex = source.writeBlob(new ByteArrayInputStream(layer));
        List<String> asked = new ArrayList<>();

        FakeExchange mount = mount(hex, "acme/base/library/debian", path -> {
            asked.add(path);
            return Optional.of(source);
        });

        assertThat(asked).as("the read is decided for the path a pull of that blob would take")
                .containsExactly("/v2/acme/base/library/debian/blobs/sha256:" + hex);
        assertThat(mount.status()).isEqualTo(201);
        assertThat(mount.responseHeader("Location")).isEqualTo("/v2/app/blobs/sha256:" + hex);
        assertThat(mount.responseHeader("Docker-Content-Digest")).isEqualTo("sha256:" + hex);
        assertThat(target.isEmpty("oci/upload-sessions")).as("no upload session was opened").isTrue();

        FakeExchange pull = new FakeExchange("GET", "/v2/app/blobs/sha256:" + hex);
        format.handle(pull, target);
        assertThat(pull.status()).isEqualTo(200);
        assertThat(pull.responseBytes()).as("the blob now serves from this repository").isEqualTo(layer);
    }

    @Test
    void a_mount_that_is_refused_is_the_same_upload_session_whatever_refused_it() throws IOException {
        String hex = source.writeBlob(new ByteArrayInputStream("held elsewhere".getBytes(StandardCharsets.UTF_8)));
        String absent = "c".repeat(64);

        // The caller may not read the other repository: the edge answers nothing, whether or not the blob is there.
        FakeExchange forbidden = mount(hex, "acme/base/library/debian", _ -> Optional.empty());
        // The caller may read it, and the blob is simply not there.
        FakeExchange missing = mount(absent, "acme/base/library/debian", _ -> Optional.of(source));
        // The blob is there and withheld by a hold.
        String held = source.writeBlob(new ByteArrayInputStream("withheld".getBytes(StandardCharsets.UTF_8)));
        Withheld.mark(source, held);
        FakeExchange withheld = mount(held, "acme/base/library/debian", _ -> Optional.of(source));
        // A name that could never be one.
        FakeExchange garbled = mount(hex, "../base", _ -> Optional.of(source));

        for (FakeExchange refused : List.of(forbidden, missing, withheld, garbled)) {
            assertThat(refused.status()).as("an ordinary upload session").isEqualTo(202);
            assertThat(refused.responseHeaders().keySet())
                    .containsExactlyInAnyOrder("Location", "Docker-Upload-UUID", "Range");
            assertThat(refused.responseHeader("Location")).startsWith("/v2/app/blobs/uploads/");
            assertThat(refused.responseHeader("Range")).isEqualTo("0-0");
        }
        assertThat(target.exists("blobs/" + hex)).as("nothing was linked").isFalse();
        assertThat(target.exists("blobs/" + held)).isFalse();
    }

    @Test
    void the_session_a_refused_mount_opens_completes_as_an_upload() throws IOException {
        byte[] layer = "uploaded after all".getBytes(StandardCharsets.UTF_8);
        String hex = HexFormat.of().formatHex(sha256(layer));
        FakeExchange refused = mount(hex, "acme/base/library/debian", _ -> Optional.empty());
        String location = refused.responseHeader("Location");

        FakeExchange put = new FakeExchange("PUT", location, layer, Map.of("digest", "sha256:" + hex), Map.of());
        format.handle(put, target);
        assertThat(put.status()).isEqualTo(201);
        assertThat(target.exists("blobs/" + hex)).isTrue();
    }

    private FakeExchange mount(String hex, String from, Function<String, Optional<ArtifactStore>> readable)
            throws IOException {
        FakeExchange mount = new FakeExchange("POST", "/v2/app/blobs/uploads/", new byte[0],
                Map.of("mount", "sha256:" + hex, "from", from), Map.of()).readable(readable);
        format.handle(mount, target);
        return mount;
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
