package build.jenesis.repository.format.oci.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.format.oci.OciFormat;
import build.jenesis.repository.format.oci.OciListingObserver;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.Withheld;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The referrers API over the real format and a filesystem store: a manifest pushed with a {@code subject} is answered
 * with {@code OCI-Subject} and joins its subject's index as it is written; {@code GET /v2/<name>/referrers/<digest>}
 * answers that index, filtered by {@code artifactType} when asked and paged by {@code Link}; the index is the stored
 * document a push maintains - a read of it walks nothing, and the rebuild regenerates it from the markers the pushes
 * wrote; a held referrer is absent from it until released; and an attachment an older client made through the tag
 * schema is found through the API.
 */
class OciReferrersTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String SBOM = "application/spdx+json";
    private static final String ATTESTATION = "application/vnd.in-toto+json";

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
    void a_manifest_pushed_with_a_subject_answers_oci_subject_and_joins_its_index() throws IOException {
        String image = image("app", "1.0");
        FakeExchange put = put("app", "sha256:" + sha256(referrer(image, SBOM, "sbom")),
                referrer(image, SBOM, "sbom"));
        assertThat(put.status()).isEqualTo(201);
        assertThat(put.responseHeader("OCI-Subject")).as("the push confirms the subject it names")
                .isEqualTo("sha256:" + image);

        FakeExchange get = get("/v2/app/referrers/sha256:" + image, Map.of());
        assertThat(get.status()).isEqualTo(200);
        assertThat(get.responseHeader("Content-Type")).isEqualTo("application/vnd.oci.image.index.v1+json");
        JsonNode index = JSON.readTree(get.responseBytes());
        assertThat(index.path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(index.path("mediaType").asString()).isEqualTo("application/vnd.oci.image.index.v1+json");
        assertThat(index.path("manifests")).hasSize(1);
        JsonNode descriptor = index.path("manifests").get(0);
        byte[] body = referrer(image, SBOM, "sbom");
        assertThat(descriptor.path("digest").asString()).isEqualTo("sha256:" + sha256(body));
        assertThat(descriptor.path("size").asLong()).isEqualTo(body.length);
        assertThat(descriptor.path("mediaType").asString()).isEqualTo("application/vnd.oci.image.manifest.v1+json");
        assertThat(descriptor.path("artifactType").asString()).isEqualTo(SBOM);
        assertThat(descriptor.path("annotations").path("org.example.kind").asString()).isEqualTo("sbom");
    }

    @Test
    void a_manifest_without_an_artifact_type_is_listed_by_its_config_media_type() throws IOException {
        String image = image("app", "1.0");
        String config = blob("app", "{}".getBytes(StandardCharsets.UTF_8));
        byte[] body = ("{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                + "\"config\":{\"mediaType\":\"application/vnd.example.config.v1+json\",\"size\":2,"
                + "\"digest\":\"sha256:" + config + "\"},\"layers\":[],"
                + "\"subject\":{\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\",\"size\":1,"
                + "\"digest\":\"sha256:" + image + "\"}}").getBytes(StandardCharsets.UTF_8);
        assertThat(put("app", "sha256:" + sha256(body), body).status()).isEqualTo(201);

        JsonNode index = JSON.readTree(get("/v2/app/referrers/sha256:" + image, Map.of()).responseBytes());
        assertThat(index.path("manifests").get(0).path("artifactType").asString())
                .as("the specification's fallback: the config descriptor's media type")
                .isEqualTo("application/vnd.example.config.v1+json");
    }

    @Test
    void a_subject_nothing_refers_to_answers_the_empty_index_and_a_malformed_digest_is_refused() throws IOException {
        FakeExchange empty = get("/v2/app/referrers/sha256:" + "a".repeat(64), Map.of());
        assertThat(empty.status()).isEqualTo(200);
        assertThat(JSON.readTree(empty.responseBytes()).path("manifests")).isEmpty();
        assertThat(StoredListing.present(store, "oci/app/manifests/" + "a".repeat(64) + "/referrers"))
                .as("asking stores nothing for a subject nothing refers to").isFalse();

        FakeExchange malformed = new FakeExchange("GET", "/v2/app/referrers/sha256:nothex");
        format.handle(malformed, store);
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(malformed.responseText()).contains("DIGEST_INVALID");
    }

    @Test
    void filtering_by_artifact_type_is_confirmed_in_oci_filters_applied() throws IOException {
        String image = image("app", "1.0");
        attach("app", image, SBOM, "one");
        attach("app", image, ATTESTATION, "two");
        attach("app", image, SBOM, "three");

        FakeExchange filtered = get("/v2/app/referrers/sha256:" + image, Map.of("artifactType", SBOM));
        assertThat(filtered.responseHeader("OCI-Filters-Applied")).isEqualTo("artifactType");
        assertThat(types(filtered)).containsExactly(SBOM, SBOM);

        FakeExchange unfiltered = get("/v2/app/referrers/sha256:" + image, Map.of());
        assertThat(unfiltered.responseHeader("OCI-Filters-Applied")).as("no filter, nothing confirmed").isNull();
        assertThat(types(unfiltered)).containsExactlyInAnyOrder(SBOM, SBOM, ATTESTATION);
    }

    @Test
    void the_index_pages_through_a_link_to_the_rest() throws IOException {
        String image = image("app", "1.0");
        Set<String> attached = new TreeSet<>();
        for (int i = 0; i < 5; i++) {
            attached.add(attach("app", image, SBOM, "part-" + i));
        }

        Set<String> seen = new TreeSet<>();
        Map<String, String> query = Map.of("n", "2");
        int pages = 0;
        while (true) {
            FakeExchange page = get("/v2/app/referrers/sha256:" + image, query);
            pages++;
            JsonNode index = JSON.readTree(page.responseBytes());
            assertThat(index.path("manifests").size()).isLessThanOrEqualTo(2);
            index.path("manifests").forEach(descriptor -> seen.add(descriptor.path("digest").asString()));
            String link = page.responseHeader("Link");
            if (link == null) {
                break;
            }
            assertThat(link).startsWith("</v2/app/referrers/sha256:" + image + "?").endsWith(">; rel=\"next\"");
            query = parameters(link.substring(link.indexOf('?') + 1, link.indexOf('>')));
        }
        assertThat(pages).isEqualTo(3);
        assertThat(seen).as("every referrer, once, across the pages").isEqualTo(attached);
    }

    @Test
    void the_index_is_read_as_stored_and_rebuilt_from_the_markers_the_pushes_wrote() throws IOException {
        String image = image("app", "1.0");
        String sbom = attach("app", image, SBOM, "sbom");
        String listing = "oci/app/manifests/" + image + "/referrers";
        byte[] stored = body(listing);

        // The marker is the durable fact the index is generated from. Take it away and a read still answers the
        // stored document - it is read, not re-derived - while the rebuild pass regenerates from what is recorded.
        store.delete("oci/app/manifests/" + image + "/referrers/" + sha256Of(sbom));
        assertThat(digests(get("/v2/app/referrers/sha256:" + image, Map.of())))
                .as("a read streams the stored index and walks nothing").containsExactly(sbom);
        assertThat(new OciListingObserver().rebuild(listing, store)).isTrue();
        assertThat(digests(get("/v2/app/referrers/sha256:" + image, Map.of())))
                .as("the rebuild regenerates the index from the markers").isEmpty();

        store.write("oci/app/manifests/" + image + "/referrers/" + sha256Of(sbom), InputStream.nullInputStream());
        new OciListingObserver().rebuild(listing, store);
        assertThat(body(listing))
                .as("regenerated from the marker, the index is byte for byte the one the push maintained")
                .isEqualTo(stored);
    }

    @Test
    void a_referrer_held_after_it_was_accepted_leaves_the_index_until_it_is_released() throws IOException {
        String image = image("app", "1.0");
        String sbom = attach("app", image, SBOM, "sbom");
        String hex = sha256Of(sbom);
        ArtifactDescriptor held = ArtifactDescriptor.at("oci", "/v2/app/manifests/" + sbom);

        Withheld.mark(store, hex, held);
        assertThat(digests(get("/v2/app/referrers/sha256:" + image, Map.of())))
                .as("a held referrer is absent from the index").isEmpty();

        Withheld.clear(store, hex, Known.absent(), held);
        assertThat(digests(get("/v2/app/referrers/sha256:" + image, Map.of())))
                .as("and rejoins it when the hold is lifted").containsExactly(sbom);
    }

    @Test
    void a_referrer_held_as_it_is_pushed_is_recorded_and_listed_only_once_released() throws IOException {
        String name = "app-" + OciScreenInterceptor.QUARANTINE_MARKER;
        // The subject is held too - the screen holds the whole name - which a referrers request does not consult.
        String image = image(name, "1.0", 202);
        byte[] body = referrer(image, SBOM, "sbom");
        String hex = sha256(body);
        FakeExchange put = put(name, "sha256:" + hex, body);
        assertThat(put.status()).isEqualTo(202);
        assertThat(put.responseHeader("OCI-Subject")).isEqualTo("sha256:" + image);
        assertThat(digests(get("/v2/" + name + "/referrers/sha256:" + image, Map.of()))).isEmpty();
        assertThat(store.exists("oci/" + name + "/manifests/" + image + "/referrers/" + hex))
                .as("the relationship is recorded while the referrer is held").isTrue();

        // A reviewer's release: the media-type record the accepted layout would have written, and the lifted hold.
        store.write("oci/types/" + hex, new ByteArrayInputStream(
                "application/vnd.oci.image.manifest.v1+json".getBytes(StandardCharsets.UTF_8)));
        Withheld.clear(store, hex, Known.absent(), ArtifactDescriptor.at("oci", "/v2/" + name + "/manifests/sha256:" + hex));
        assertThat(digests(get("/v2/" + name + "/referrers/sha256:" + image, Map.of())))
                .containsExactly("sha256:" + hex);
    }

    @Test
    void a_rejected_referrer_records_nothing() throws IOException {
        String subject = "b".repeat(64);
        String name = "app-" + OciScreenInterceptor.REJECT_MARKER;
        byte[] body = referrer(subject, SBOM, "sbom");
        FakeExchange put = put(name, "sha256:" + sha256(body), body);
        assertThat(put.status()).isEqualTo(403);
        assertThat(put.responseHeader("OCI-Subject")).isNull();
        assertThat(store.isEmpty("oci/" + name + "/manifests")).isTrue();
    }

    @Test
    void an_attachment_made_through_the_tag_schema_is_found_through_the_api() throws IOException {
        String image = image("app", "1.0");
        // What an older client did against a registry that answered no referrers API: push the referrer, then an
        // index tagged sha256-<subject hex> listing it. A registry from before kept neither marker nor index.
        String sbom = attach("app", image, SBOM, "sbom");
        store.delete("oci/app/manifests/" + image + "/referrers/" + sha256Of(sbom));
        StoredListing.forget(store, "oci/app/manifests/" + image + "/referrers");
        byte[] fallback = ("{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.index.v1+json\","
                + "\"manifests\":[{\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\",\"size\":1,"
                + "\"digest\":\"" + sbom + "\",\"artifactType\":\"" + SBOM + "\"}]}").getBytes(StandardCharsets.UTF_8);
        FakeExchange tagged = new FakeExchange("PUT", "/v2/app/manifests/sha256-" + image, fallback, Map.of(),
                Map.of("Content-Type", "application/vnd.oci.image.index.v1+json"));
        format.handle(tagged, store);
        assertThat(tagged.status()).isEqualTo(201);

        assertThat(digests(get("/v2/app/referrers/sha256:" + image, Map.of())))
                .as("the tag-schema index back-fills the first read").containsExactly(sbom);
    }

    // ---- helpers ----

    /** An image of one layer, tagged; its manifest hex. */
    private String image(String name, String tag) throws IOException {
        return image(name, tag, 201);
    }

    private String image(String name, String tag, int expected) throws IOException {
        String config = blob(name, ("config of " + name + ":" + tag).getBytes(StandardCharsets.UTF_8));
        String layer = blob(name, ("layer of " + name + ":" + tag).getBytes(StandardCharsets.UTF_8));
        byte[] body = ("{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                + "\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\",\"size\":1,"
                + "\"digest\":\"sha256:" + config + "\"},\"layers\":[{\"mediaType\":"
                + "\"application/vnd.oci.image.layer.v1.tar+gzip\",\"size\":1,\"digest\":\"sha256:" + layer + "\"}]}")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(put(name, tag, body).status()).isEqualTo(expected);
        return sha256(body);
    }

    /** An artifact manifest of {@code artifactType} whose subject is {@code subject}. */
    private static byte[] referrer(String subject, String artifactType, String kind) {
        return ("{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                + "\"artifactType\":\"" + artifactType + "\","
                + "\"config\":{\"mediaType\":\"application/vnd.oci.empty.v1+json\",\"size\":2,"
                + "\"digest\":\"sha256:" + sha256("{}".getBytes(StandardCharsets.UTF_8)) + "\"},\"layers\":[],"
                + "\"subject\":{\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\",\"size\":1,"
                + "\"digest\":\"sha256:" + subject + "\"},"
                + "\"annotations\":{\"org.example.kind\":\"" + kind + "\"}}").getBytes(StandardCharsets.UTF_8);
    }

    /** Attach a referrer by digest, as {@code oras attach} does; its digest. */
    private String attach(String name, String subject, String artifactType, String kind) throws IOException {
        byte[] body = referrer(subject, artifactType, kind);
        FakeExchange put = put(name, "sha256:" + sha256(body), body);
        assertThat(put.status()).isEqualTo(201);
        return "sha256:" + sha256(body);
    }

    private String blob(String name, byte[] content) throws IOException {
        String hex = sha256(content);
        FakeExchange post = new FakeExchange("POST", "/v2/" + name + "/blobs/uploads/", content,
                Map.of("digest", "sha256:" + hex), Map.of());
        format.handle(post, store);
        assertThat(post.status()).isEqualTo(201);
        return hex;
    }

    private FakeExchange put(String name, String reference, byte[] body) throws IOException {
        FakeExchange put = new FakeExchange("PUT", "/v2/" + name + "/manifests/" + reference, body, Map.of(),
                Map.of("Content-Type", "application/vnd.oci.image.manifest.v1+json"));
        format.handle(put, store);
        return put;
    }

    private FakeExchange get(String path, Map<String, String> query) throws IOException {
        FakeExchange get = new FakeExchange("GET", path, new byte[0], query, Map.of());
        format.handle(get, store);
        assertThat(get.status()).isEqualTo(200);
        return get;
    }

    /** The body of a stored listing, as a read would stream it. */
    private byte[] body(String listing) throws IOException {
        try (StoredListing.Served served = StoredListing.served(store, listing).orElseThrow()) {
            return served.bytes();
        }
    }

    private static List<String> types(FakeExchange answer) {
        List<String> types = new ArrayList<>();
        JSON.readTree(answer.responseBytes()).path("manifests")
                .forEach(descriptor -> types.add(descriptor.path("artifactType").asString()));
        return types;
    }

    private static List<String> digests(FakeExchange answer) {
        List<String> digests = new ArrayList<>();
        JSON.readTree(answer.responseBytes()).path("manifests")
                .forEach(descriptor -> digests.add(descriptor.path("digest").asString()));
        return digests;
    }

    private static Map<String, String> parameters(String query) {
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            parameters.put(pair.substring(0, equals),
                    URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return parameters;
    }

    private static String sha256Of(String digest) {
        return digest.substring("sha256:".length());
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
