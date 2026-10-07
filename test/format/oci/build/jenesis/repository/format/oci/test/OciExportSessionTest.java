package build.jenesis.repository.format.oci.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.format.oci.OciFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An image exported to a registry that declines the single-request blob upload.
 *
 * <p>The Distribution specification lets a registry decline a {@code POST} that carries the whole blob and its digest:
 * it answers {@code 202} with an upload session in {@code Location}, and the client puts the blob there. The
 * {@code registry:2} image does exactly that. Taken for done, the {@code 202} leaves every blob unsent and the manifest
 * refused for naming blobs the registry lacks; here the target is scripted to decline, and every blob has to arrive
 * through its session before the manifest is put.
 */
class OciExportSessionTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private final OciFormat format = new OciFormat();

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
    }

    @Test
    void a_registry_that_opens_a_session_is_given_the_blob_there_before_the_manifest() throws IOException {
        String config = push("library/app", "config".getBytes(StandardCharsets.UTF_8));
        String layer = push("library/app", "layer".getBytes(StandardCharsets.UTF_8));
        byte[] manifest = ("{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                + "\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\",\"size\":6,"
                + "\"digest\":\"sha256:" + config + "\"},\"layers\":[{\"mediaType\":"
                + "\"application/vnd.oci.image.layer.v1.tar\",\"size\":5,\"digest\":\"sha256:" + layer + "\"}]}")
                .getBytes(StandardCharsets.UTF_8);
        FakeExchange put = new FakeExchange("PUT", "/v2/library/app/manifests/1.0", manifest, Map.of(),
                Map.of("Content-Type", "application/vnd.oci.image.manifest.v1+json"));
        format.handle(put, store);
        assertThat(put.status()).isEqualTo(201);

        SessionRegistry registry = new SessionRegistry();
        RepositoryExporter.Exported exported = format.export(store, "library/app", "1.0", registry);

        assertThat(exported).isEqualTo(RepositoryExporter.Exported.PUBLISHED);
        assertThat(registry.blobs).as("each blob arrived through the session the registry opened")
                .containsExactlyInAnyOrder(config, layer);
        assertThat(registry.requests.getLast()).as("and the manifest went last, once its blobs were there")
                .isEqualTo("PUT library/app/manifests/1.0");
    }

    private String push(String name, byte[] content) throws IOException {
        String hex = HexFormat.of().formatHex(digest(content));
        FakeExchange post = new FakeExchange("POST", "/v2/" + name + "/blobs/uploads/", content,
                Map.of("digest", "sha256:" + hex), Map.of());
        format.handle(post, store);
        assertThat(post.status()).isEqualTo(201);
        return hex;
    }

    private static byte[] digest(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A registry that declines a whole-blob {@code POST} with a session, takes a blob put into that session under its
     *  digest, and refuses a manifest naming a blob it does not hold - as {@code registry:2} does. */
    private static final class SessionRegistry implements ExportTarget {

        private final Set<String> blobs = new LinkedHashSet<>();
        private final List<String> requests = new ArrayList<>();
        private int sessions;

        @Override
        public Response send(Request request) throws IOException {
            String path = request.path();
            requests.add(request.method() + " " + path.replaceAll("\\?.*", ""));
            if ("POST".equals(request.method()) && path.contains("/blobs/uploads/")) {
                return new Response(202, "", Optional.of("library/app/blobs/uploads/session-" + ++sessions
                        + "?_state=opaque"));
            }
            if ("PUT".equals(request.method()) && path.contains("/blobs/uploads/session-")) {
                String hex = path.replaceAll(".*digest=sha256:", "");
                byte[] body;
                try (InputStream in = request.body().open()) {
                    body = in.readAllBytes();
                }
                if (!HexFormat.of().formatHex(digest(body)).equals(hex)) {
                    return new Response(400, "digest mismatch");
                }
                blobs.add(hex);
                return new Response(201, "");
            }
            if ("PUT".equals(request.method()) && path.contains("/manifests/")) {
                return blobs.size() == 2 ? new Response(201, "") : new Response(400, "MANIFEST_BLOB_UNKNOWN");
            }
            return new Response(404, "");
        }

        @Override
        public Optional<String> sha256(String path) {
            return Optional.empty();
        }

        @Override
        public Optional<Credential> credential() {
            return Optional.empty();
        }
    }
}
