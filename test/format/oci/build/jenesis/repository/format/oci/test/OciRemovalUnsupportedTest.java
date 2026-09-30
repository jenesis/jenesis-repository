package build.jenesis.repository.format.oci.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.cleanup.VersionRemoval;
import build.jenesis.repository.format.oci.OciFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code DELETE} this deployment cannot carry out, and a write the edge refuses, both answered in the Distribution
 * error envelope. This module's graph carries no inventory, so nothing removes a version through the one path - and
 * the format answers {@code 405 UNSUPPORTED} rather than deleting around it; the removal through the inventory is
 * proved where the graph composes one.
 */
class OciRemovalUnsupportedTest {

    @TempDir
    Path root;

    private final OciFormat format = new OciFormat();

    @Test
    void with_no_inventory_a_delete_is_unsupported_and_removes_nothing() throws IOException {
        assertThat(VersionRemoval.installed().supported()).as("this graph carries no inventory").isFalse();
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        byte[] manifest = ("{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                + "\"layers\":[]}").getBytes(StandardCharsets.UTF_8);
        FakeExchange put = new FakeExchange("PUT", "/v2/app/manifests/1.0", manifest, Map.of(),
                Map.of("Content-Type", "application/vnd.oci.image.manifest.v1+json"));
        format.handle(put, store);
        assertThat(put.status()).isEqualTo(201);

        FakeExchange delete = new FakeExchange("DELETE", "/v2/app/manifests/1.0");
        format.handle(delete, store);
        assertThat(delete.status()).isEqualTo(405);
        assertThat(delete.responseHeader("Content-Type")).isEqualTo("application/json");
        assertThat(delete.responseText()).contains("\"code\":\"UNSUPPORTED\"");
        assertThat(delete.audited()).as("nothing removed, nothing recorded").isEmpty();

        FakeExchange pull = new FakeExchange("GET", "/v2/app/manifests/1.0");
        format.handle(pull, store);
        assertThat(pull.status()).isEqualTo(200);
    }

    @Test
    void a_write_the_edge_refuses_is_answered_with_the_distribution_error_envelope() throws IOException {
        FakeExchange refused = new FakeExchange("DELETE", "/v2/app/manifests/1.0");
        format.refuse(refused, 405);
        assertThat(refused.status()).isEqualTo(405);
        assertThat(refused.responseHeader("Content-Type")).isEqualTo("application/json");
        assertThat(refused.responseText()).startsWith("{\"errors\":[{\"code\":\"UNSUPPORTED\",\"message\":");
    }
}
