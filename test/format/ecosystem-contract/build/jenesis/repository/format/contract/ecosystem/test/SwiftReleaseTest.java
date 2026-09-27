package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;
import module org.junit.jupiter.params;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.StoredListing;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a Swift release publish writes beside the archive: the release document a client verifies the archive
 * against. The archive's refusal and its race are {@link ReleaseImmutabilityTest}'s; this holds the document to
 * two things that suite cannot see - that a publish whose document never landed is repaired by sending it again, and
 * that the document is built from the publisher's metadata as a parsed value, so nothing a publisher sends can stand
 * beside the {@code checksum} the store computed.
 */
class SwiftReleaseTest {

    private static final String BOUNDARY = "swift-release-boundary";

    private static final String RELEASE = "/swift/registry/contract/widget/1.0.0";

    /** Where the release document is stored, which a publish that stopped after linking the archive never wrote. */
    private static final String DOCUMENT = "swift/registry/contract/widget/1.0.0/metadata";

    @TempDir
    Path root;

    @AfterEach
    void settle() {
        StoredListing.settle();
    }

    @Test
    void a_publish_whose_release_document_never_landed_converges_when_sent_again() throws IOException {
        ArtifactStore store = store();
        byte[] archive = "the source archive".getBytes(StandardCharsets.UTF_8);
        assertThat(publish(store, archive, "{\"author\":{\"name\":\"Contract\"}}").status()).isEqualTo(201);
        store.delete(DOCUMENT);
        assertThat(get(store, RELEASE).status()).as("the release has no document, as after a crash").isEqualTo(404);

        ContractExchange again = publish(store, archive, "{\"author\":{\"name\":\"Contract\"}}");

        assertThat(again.status()).as("the same archive again is accepted rather than refused as a conflict")
                .isEqualTo(201);
        ContractExchange document = get(store, RELEASE);
        assertThat(document.status()).isEqualTo(200);
        assertThat(document.responseText()).contains("\"checksum\":\"" + Packages.sha256(archive) + "\"")
                .contains("\"metadata\":{\"author\":{\"name\":\"Contract\"}}");
    }

    /** Not one JSON object: a second value spliced after the first (which a text splice would have written into the
     *  document beside the checksum), an array, a string, and text that is not JSON at all. */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"author\":{\"name\":\"x\"}},\"checksum\":\"forged\"",
            "[\"an\",\"array\"]",
            "\"a string\"",
            "{not json"})
    void metadata_that_is_not_one_json_object_is_refused_and_nothing_is_released(String metadata)
            throws IOException {
        ArtifactStore store = store();

        ContractExchange refused = publish(store, "the source archive".getBytes(StandardCharsets.UTF_8), metadata);

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.responseHeader("Content-Type")).isEqualTo("application/problem+json");
        assertThat(get(store, RELEASE + ".zip").status()).as("no archive is linked").isEqualTo(404);
        assertThat(get(store, RELEASE).status()).as("and no document written").isEqualTo(404);
    }

    @Test
    void the_document_names_the_stored_checksum_whatever_the_metadata_says() throws IOException {
        ArtifactStore store = store();
        byte[] archive = "the source archive".getBytes(StandardCharsets.UTF_8);

        publish(store, archive, "{\"checksum\":\"forged\",\"resources\":[]}");

        String document = get(store, RELEASE).responseText();
        assertThat(document).startsWith("{\"id\":\"contract.widget\",\"version\":\"1.0.0\",\"resources\":[{"
                + "\"name\":\"source-archive\",\"type\":\"application/zip\",\"checksum\":\""
                + Packages.sha256(archive) + "\"}],\"metadata\":{\"checksum\":\"forged\",\"resources\":[]}}");
    }

    @Test
    void other_bytes_for_a_released_version_are_refused_with_the_specifications_problem_document()
            throws IOException {
        ArtifactStore store = store();
        publish(store, "the source archive".getBytes(StandardCharsets.UTF_8), null);

        ContractExchange refused = publish(store, "another archive".getBytes(StandardCharsets.UTF_8), null);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.responseHeader("Content-Type")).isEqualTo("application/problem+json");
        assertThat(refused.responseText()).contains("\"status\":409")
                .contains("contract.widget 1.0.0 is already published with other content");
    }

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    private static ContractExchange publish(ArtifactStore store, byte[] archive, String metadata) throws IOException {
        ContractExchange exchange = ContractExchange.of("PUT", RELEASE, Packages.swiftForm(BOUNDARY, archive, metadata,
                        null))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
        new SwiftFormatFixture().serving().handle(exchange, store);
        return exchange;
    }

    private static ContractExchange get(ArtifactStore store, String path) throws IOException {
        ContractExchange exchange = ContractExchange.of("GET", path);
        new SwiftFormatFixture().serving().handle(exchange, store);
        return exchange;
    }
}
