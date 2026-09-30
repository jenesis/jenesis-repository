package build.jenesis.repository.format.oci.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.format.oci.OciFormat;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.Withheld;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The signature evidence an image carries through its referrers, beside cosign's tag convention: a cosign signature
 * attached as a referrer is the same covering evidence the {@code .sig} tag yields, a Sigstore bundle attached as one
 * is evidence over the manifest itself, a referrer of any other kind is none, and a held one is not read. And a
 * referrer pushed by digest says, from its own bytes, which image it covers - so a signature attached after its image
 * re-derives that image's verdict.
 *
 * <p>The material is read the way the signature screen reads it: siblings by request path, through the format's own
 * serving, and the format's own records by key.
 */
class OciReferrerSignaturesTest {

    private static final String BUNDLE = "application/vnd.dev.sigstore.bundle.v0.3+json";
    private static final String COSIGN = "application/vnd.dev.cosign.artifact.sig.v1+json";

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
    void a_sigstore_bundle_attached_as_a_referrer_is_evidence_over_the_manifest() throws IOException {
        byte[] image = image();
        String subject = sha256(image);
        byte[] bundle = "{\"mediaType\":\"application/vnd.dev.sigstore.bundle.v0.3+json\"}".getBytes(StandardCharsets.UTF_8);
        String layer = blob(bundle);
        String referrer = push(artifact(subject, BUNDLE, "\"layers\":[{\"mediaType\":\"" + BUNDLE + "\",\"size\":"
                + bundle.length + ",\"digest\":\"sha256:" + layer + "\"}]"));

        List<ArtifactSignatures.Evidence> evidence = format.evidence("/v2/app/manifests/1.0", material(image));
        assertThat(evidence).hasSize(1);
        ArtifactSignatures.Evidence found = evidence.getFirst();
        assertThat(found.scheme()).isEqualTo(ArtifactSignatures.Scheme.SIGSTORE_BUNDLE);
        assertThat(found.signature()).as("the bundle, as the referrer's layer holds it").isEqualTo(bundle);
        assertThat(found.named()).as("a bundle signs the manifest itself, not a document naming it").isNull();
        try (InputStream signed = found.signed().open()) {
            assertThat(signed.readAllBytes()).isEqualTo(image);
        }
        assertThat(found.location()).isEqualTo("/v2/app/manifests/sha256:" + referrer + "#0");
    }

    @Test
    void a_cosign_signature_attached_as_a_referrer_is_the_evidence_its_tag_would_be() throws IOException {
        byte[] image = image();
        String subject = sha256(image);
        byte[] payload = ("{\"critical\":{\"image\":{\"docker-manifest-digest\":\"sha256:" + subject + "\"}}}")
                .getBytes(StandardCharsets.UTF_8);
        String layer = blob(payload);
        String layers = "\"layers\":[{\"mediaType\":\"application/vnd.dev.cosign.simplesigning.v1+json\",\"size\":"
                + payload.length + ",\"digest\":\"sha256:" + layer + "\",\"annotations\":{"
                + "\"dev.cosignproject.cosign/signature\":\"c2lnbmF0dXJl\"}}]";
        String referrer = push(artifact(subject, COSIGN, layers));

        List<ArtifactSignatures.Evidence> evidence = format.evidence("/v2/app/manifests/1.0", material(image));
        assertThat(evidence).hasSize(1);
        ArtifactSignatures.Evidence found = evidence.getFirst();
        assertThat(found.named()).as("the payload is a document naming the image").isNotNull();
        try (InputStream signed = found.signed().open()) {
            assertThat(found.named().sha256(signed.readAllBytes())).hasValue(subject);
        }
        assertThat(found.location()).isEqualTo("/v2/app/manifests/sha256:" + referrer + "#0");

        // The same signature under the tag convention is found as well, and neither hides the other.
        byte[] tagged = artifact(null, COSIGN, layers);
        FakeExchange put = new FakeExchange("PUT", "/v2/app/manifests/sha256-" + subject + ".sig", tagged, Map.of(),
                Map.of("Content-Type", "application/vnd.oci.image.manifest.v1+json"));
        format.handle(put, store);
        assertThat(put.status()).isEqualTo(201);
        assertThat(format.evidence("/v2/app/manifests/1.0", material(image)))
                .extracting(ArtifactSignatures.Evidence::location)
                .containsExactly("/v2/app/manifests/sha256-" + subject + ".sig#0",
                        "/v2/app/manifests/sha256:" + referrer + "#0");
    }

    @Test
    void a_referrer_that_is_no_signature_or_is_held_is_not_evidence() throws IOException {
        byte[] image = image();
        String subject = sha256(image);
        push(artifact(subject, "application/spdx+json", "\"layers\":[]"));
        assertThat(format.evidence("/v2/app/manifests/1.0", material(image))).as("an SBOM is not a signature").isEmpty();

        byte[] bundle = "{}".getBytes(StandardCharsets.UTF_8);
        String layer = blob(bundle);
        String referrer = push(artifact(subject, BUNDLE, "\"layers\":[{\"mediaType\":\"" + BUNDLE + "\",\"size\":2,"
                + "\"digest\":\"sha256:" + layer + "\"}]"));
        Withheld.mark(store, referrer, ArtifactDescriptor.at("oci", "/v2/app/manifests/sha256:" + referrer));
        assertThat(format.evidence("/v2/app/manifests/1.0", material(image)))
                .as("a held signature is absent from the index and is not read").isEmpty();
    }

    @Test
    void a_signature_referrer_pushed_by_digest_covers_the_image_its_subject_names() throws IOException {
        String subject = "d".repeat(64);
        byte[] signature = artifact(subject, BUNDLE, "\"layers\":[]");
        String path = "/v2/app/manifests/sha256:" + sha256(signature);
        assertThat(format.covers(path)).as("the path names no subject").isEmpty();
        assertThat(format.covers(path, () -> new ByteArrayInputStream(signature)))
                .hasValue("/v2/app/manifests/sha256:" + subject);

        byte[] sbom = artifact(subject, "application/spdx+json", "\"layers\":[]");
        assertThat(format.covers("/v2/app/manifests/sha256:" + sha256(sbom), () -> new ByteArrayInputStream(sbom)))
                .as("an attachment that is no signature covers nothing").isEmpty();
        byte[] plain = artifact(null, BUNDLE, "\"layers\":[]");
        assertThat(format.covers("/v2/app/manifests/sha256:" + sha256(plain), () -> new ByteArrayInputStream(plain)))
                .as("nor does a manifest with no subject").isEmpty();
        assertThat(format.covers("/v2/app/manifests/sha256-" + subject + ".sig",
                () -> { throw new AssertionError("the tag convention needs no bytes"); }))
                .hasValue("/v2/app/manifests/sha256:" + subject);
    }

    // ---- helpers ----

    /** A one-layer image tagged {@code 1.0}; its manifest bytes. */
    private byte[] image() throws IOException {
        String config = blob("config".getBytes(StandardCharsets.UTF_8));
        String layer = blob("layer".getBytes(StandardCharsets.UTF_8));
        byte[] body = ("{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                + "\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\",\"size\":6,"
                + "\"digest\":\"sha256:" + config + "\"},\"layers\":[{\"mediaType\":"
                + "\"application/vnd.oci.image.layer.v1.tar+gzip\",\"size\":5,\"digest\":\"sha256:" + layer + "\"}]}")
                .getBytes(StandardCharsets.UTF_8);
        FakeExchange put = new FakeExchange("PUT", "/v2/app/manifests/1.0", body, Map.of(),
                Map.of("Content-Type", "application/vnd.oci.image.manifest.v1+json"));
        format.handle(put, store);
        assertThat(put.status()).isEqualTo(201);
        return body;
    }

    private static byte[] artifact(String subject, String artifactType, String layers) {
        return ("{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                + "\"artifactType\":\"" + artifactType + "\","
                + "\"config\":{\"mediaType\":\"application/vnd.oci.empty.v1+json\",\"size\":2,"
                + "\"digest\":\"sha256:" + sha256("{}".getBytes(StandardCharsets.UTF_8)) + "\"}," + layers
                + (subject == null ? "" : ",\"subject\":{\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                        + "\"size\":1,\"digest\":\"sha256:" + subject + "\"}") + "}").getBytes(StandardCharsets.UTF_8);
    }

    /** Push a manifest by its digest; its hex. */
    private String push(byte[] body) throws IOException {
        String hex = sha256(body);
        FakeExchange put = new FakeExchange("PUT", "/v2/app/manifests/sha256:" + hex, body, Map.of(),
                Map.of("Content-Type", "application/vnd.oci.image.manifest.v1+json"));
        format.handle(put, store);
        assertThat(put.status()).isEqualTo(201);
        return hex;
    }

    private String blob(byte[] content) throws IOException {
        String hex = sha256(content);
        FakeExchange post = new FakeExchange("POST", "/v2/app/blobs/uploads/", content,
                Map.of("digest", "sha256:" + hex), Map.of());
        format.handle(post, store);
        assertThat(post.status()).isEqualTo(201);
        return hex;
    }

    /** The material the signature screen hands a format: siblings as the registry serves them, records by key. */
    private ArtifactSignatures.Material material(byte[] body) {
        return new ArtifactSignatures.Material() {
            @Override
            public Optional<PublishInterceptor.Content.Bounded> sibling(String path, int limit) throws IOException {
                FakeExchange get = new FakeExchange("GET", path);
                format.handle(get, store);
                if (get.status() != 200) {
                    return Optional.empty();
                }
                byte[] content = get.responseBytes();
                return Optional.of(content.length > limit
                        ? new PublishInterceptor.Content.Bounded(Arrays.copyOf(content, limit), true)
                        : new PublishInterceptor.Content.Bounded(content, false));
            }

            @Override
            public Optional<ArtifactSignatures.Signed> body() {
                return Optional.of(() -> new ByteArrayInputStream(body));
            }

            @Override
            public Optional<PublishInterceptor.Content.Bounded> recorded(String key, int limit) throws IOException {
                return store.readVersioned(key).map(recorded -> recorded.content().length > limit
                        ? new PublishInterceptor.Content.Bounded(Arrays.copyOf(recorded.content(), limit), true)
                        : new PublishInterceptor.Content.Bounded(recorded.content(), false));
            }
        };
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
