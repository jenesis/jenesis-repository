package build.jenesis.repository.store.azure.test;

import build.jenesis.repository.store.azure.AzureArtifactStoreProvider;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code azure-blob} blob endpoint must be {@code https} by default, exactly as the {@code s3} and {@code gcs}
 * endpoint overrides must be - the one difference being where the scheme lives. Azure carries it <em>inside</em>
 * {@code jenesis.repository.azure-blob.connection-string}, beside the account key, so the rule its siblings apply to an
 * endpoint key of their own never reached this backend and a {@code DefaultEndpointsProtocol=http} put the shared-key
 * signature and every artifact byte on a plaintext wire with no operator signal at all. A plaintext endpoint - a local
 * Azurite container - is an explicit opt-out: {@code jenesis.repository.azure-blob.allow-insecure-endpoint=true}.
 *
 * <p>The extraction is pinned as directly as the screen, because getting it wrong disarms the screen silently: a
 * connection string whose endpoint this reads as {@code null} is one the screen waves through. Needs no Docker, so it
 * always runs; the opted-in {@code http} path against a real container is exercised by
 * {@code AzureArtifactStoreProviderTest} and by the {@code azure-blob} leg of the shared store-contract kit.
 */
class AzureEndpointSchemeTest {

    private static final String KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    @Test
    void a_plaintext_endpoint_is_refused_by_default() {
        assertThatThrownBy(() -> AzureArtifactStoreProvider.secureEndpoint("http://127.0.0.1:10000/acme", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https")
                .hasMessageContaining("jenesis.repository.azure-blob.allow-insecure-endpoint");
    }

    @Test
    void the_opt_in_allows_a_plaintext_endpoint() {
        assertThat(AzureArtifactStoreProvider.secureEndpoint("http://127.0.0.1:10000/acme", "true"))
                .isEqualTo(URI.create("http://127.0.0.1:10000/acme"));
    }

    @Test
    void an_https_endpoint_is_always_accepted() {
        assertThat(AzureArtifactStoreProvider.secureEndpoint("https://acme.blob.core.windows.net", null))
                .isEqualTo(URI.create("https://acme.blob.core.windows.net"));
    }

    @Test
    void an_explicit_blob_endpoint_wins_over_the_default_protocol() {
        // Blob traffic goes to BlobEndpoint wherever it appears, so that is the value the screen has to judge - a
        // string declaring https by default and an http BlobEndpoint is a plaintext deployment.
        assertThat(AzureArtifactStoreProvider.blobEndpoint("DefaultEndpointsProtocol=https;AccountName=acme;AccountKey="
                + KEY + ";BlobEndpoint=http://127.0.0.1:10000/acme;")).isEqualTo("http://127.0.0.1:10000/acme");
        assertThat(AzureArtifactStoreProvider.blobEndpoint("BlobEndpoint=https://acme.blob.core.windows.net;"
                + "DefaultEndpointsProtocol=http;AccountName=acme;AccountKey=" + KEY))
                .isEqualTo("https://acme.blob.core.windows.net");
    }

    @Test
    void the_default_protocol_carries_the_scheme_when_no_blob_endpoint_is_named() {
        assertThat(AzureArtifactStoreProvider.blobEndpoint(
                "DefaultEndpointsProtocol=https;AccountName=acme;AccountKey=" + KEY)).startsWith("https://");
        assertThat(AzureArtifactStoreProvider.blobEndpoint(
                "defaultendpointsprotocol=http;AccountName=acme;AccountKey=" + KEY)).startsWith("http://");
    }

    @Test
    void the_development_storage_shorthand_expands_to_azurites_plaintext_loopback() {
        assertThat(AzureArtifactStoreProvider.blobEndpoint("UseDevelopmentStorage=true;")).startsWith("http://");
        assertThatThrownBy(() -> AzureArtifactStoreProvider.secureEndpoint(
                AzureArtifactStoreProvider.blobEndpoint("UseDevelopmentStorage=true;"), null))
                .as("the shorthand is Azurite, so it is plaintext and needs the same opt-out an explicit one does")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void a_string_declaring_no_endpoint_at_all_is_left_to_the_sdk() {
        // Neither BlobEndpoint nor DefaultEndpointsProtocol: a shape the SDK itself refuses, so the screen adds no
        // second diagnostic of its own rather than guessing a scheme it was never told.
        assertThat(AzureArtifactStoreProvider.blobEndpoint("AccountName=acme;AccountKey=" + KEY)).isNull();
        assertThat(AzureArtifactStoreProvider.blobEndpoint("")).isNull();
        assertThat(AzureArtifactStoreProvider.secureEndpoint(null, null)).isNull();
    }
}
