package build.jenesis.repository.format.oci.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.format.oci.OciFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Withheld;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A held image is served to the one caller that may read what the repository holds for review - the content
 * scanner its hold waits on - and to nobody else: the held manifest by its digest, under the media type it declares
 * of itself, and a withheld blob by its digest. A tag still names nothing, since nothing a held manifest would serve
 * through is laid out until its release.
 */
class OciHeldReadTest {

    private static final String TYPE = "application/vnd.docker.distribution.manifest.v2+json";

    @TempDir
    Path root;

    private ArtifactStore store;
    private final OciFormat format = new OciFormat();

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private FakeExchange get(String path, boolean readsHeld) throws IOException {
        FakeExchange get = new FakeExchange("GET", path);
        if (readsHeld) {
            get.readingHeld();
        }
        format.handle(get, store);
        return get;
    }

    private String heldManifest() throws IOException {
        byte[] manifest = ("{\"schemaVersion\":2,\"mediaType\":\"" + TYPE + "\"}").getBytes(StandardCharsets.UTF_8);
        FakeExchange put = new FakeExchange("PUT", "/v2/gate-quarantine/app/manifests/1.0", manifest,
                Map.of(), Map.of("Content-Type", TYPE));
        format.handle(put, store);
        assertThat(put.status()).as("the screen holds the push").isEqualTo(202);
        return sha256(manifest);
    }

    @Test
    void a_held_manifest_is_served_by_digest_to_a_caller_that_reads_held_content() throws IOException {
        String hex = heldManifest();

        FakeExchange scanner = get("/v2/gate-quarantine/app/manifests/sha256:" + hex, true);

        assertThat(scanner.status()).isEqualTo(200);
        assertThat(scanner.responseHeader("Content-Type")).isEqualTo(TYPE);
        assertThat(scanner.responseHeader("Docker-Content-Digest")).isEqualTo("sha256:" + hex);
        assertThat(sha256(scanner.responseBytes())).isEqualTo(hex);
    }

    @Test
    void it_stays_withheld_from_every_other_reader() throws IOException {
        String hex = heldManifest();

        assertThat(get("/v2/gate-quarantine/app/manifests/sha256:" + hex, false).status()).isEqualTo(404);
    }

    @Test
    void its_tag_names_nothing_even_to_the_scanner() throws IOException {
        heldManifest();

        assertThat(get("/v2/gate-quarantine/app/manifests/1.0", true).status()).isEqualTo(404);
    }

    @Test
    void a_blob_that_was_never_screened_as_a_manifest_is_not_served_as_one() throws IOException {
        byte[] layer = "not a manifest".getBytes(StandardCharsets.UTF_8);
        String hex = store.writeBlob(new ByteArrayInputStream(layer));

        assertThat(get("/v2/gate-quarantine/app/manifests/sha256:" + hex, true).status()).isEqualTo(404);
    }

    @Test
    void a_withheld_blob_is_served_to_the_scanner_alone() throws IOException {
        byte[] layer = "a layer of a retroactively held image".getBytes(StandardCharsets.UTF_8);
        String hex = store.writeBlob(new ByteArrayInputStream(layer));
        Withheld.mark(store, hex);

        assertThat(get("/v2/held/app/blobs/sha256:" + hex, false).status()).isEqualTo(404);
        FakeExchange scanner = get("/v2/held/app/blobs/sha256:" + hex, true);
        assertThat(scanner.status()).isEqualTo(200);
        assertThat(scanner.responseBytes()).isEqualTo(layer);
    }
}
