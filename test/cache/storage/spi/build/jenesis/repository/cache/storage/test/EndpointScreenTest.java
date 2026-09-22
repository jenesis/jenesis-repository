package build.jenesis.repository.cache.storage.test;

import module org.junit.jupiter.api;
import build.jenesis.repository.cache.storage.Endpoints;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boundary coverage for the shared https-only transport screen ({@code Endpoints.secure}) in isolation, beside the
 * {@code CacheStorageContract} leg that proves the three endpoint-configured backends really apply it.
 *
 * <p>It exists because the cache {@code s3} and {@code gcs} backends accepted a plaintext {@code http://} endpoint in
 * silence, where its artifact-store siblings had refused one since they were written: a mistyped scheme
 * put the backend's SigV4 signature and every cached byte on a wire a MITM can read and tamper with, and nothing
 * anywhere said so, because a plaintext exchange succeeds. {@code azure-blob} carried the same gap through its
 * connection string, where the account key and the transport selection travel in one value.
 *
 * <p>The rule, the opt-out spelling and the {@link Boolean#parseBoolean} reading of the opt-out are the store's,
 * reproduced here rather than invented, so one {@code JENREG_S3_ALLOW_INSECURE_ENDPOINT} governs both stores of a
 * deployment that runs the artifact store and the cache side by side.
 */
class EndpointScreenTest {

    private static final String ENDPOINT_KEY = "jenreg.s3.endpoint";
    private static final String ALLOW_KEY = "jenreg.s3.allow-insecure-endpoint";

    @Test
    void an_https_endpoint_passes_and_is_handed_back_parsed() {
        assertThat(Endpoints.secure(ENDPOINT_KEY, "https://s3.example.com", ALLOW_KEY, null))
                .hasToString("https://s3.example.com");
        // The scheme comparison is case-insensitive: a URI's scheme is case-insensitive by RFC 3986, so an operator
        // writing HTTPS:// must not be refused for a spelling the client itself treats as identical.
        assertThat(Endpoints.secure(ENDPOINT_KEY, "HTTPS://s3.example.com", ALLOW_KEY, null))
                .hasToString("HTTPS://s3.example.com");
    }

    @Test
    void a_plaintext_endpoint_is_refused_and_the_refusal_is_actionable() {
        assertThatThrownBy(() -> Endpoints.secure(ENDPOINT_KEY, "http://localhost:9000", ALLOW_KEY, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ENDPOINT_KEY)
                .hasMessageContaining("JENREG_S3_ENDPOINT")
                .hasMessageContaining("http://localhost:9000")
                .hasMessageContaining(ALLOW_KEY)
                .hasMessageContaining("JENREG_S3_ALLOW_INSECURE_ENDPOINT");
    }

    @Test
    void a_scheme_that_is_neither_https_nor_absent_is_refused_too() {
        // The screen admits exactly https, rather than refusing a denylist of known-plaintext schemes: anything else
        // is either plaintext or something no S3/blob client speaks, and both belong on the refusing side.
        for (String endpoint : new String[]{"ftp://store.example.com", "s3://bucket", "localhost:9000", "//host"}) {
            assertThatThrownBy(() -> Endpoints.secure(ENDPOINT_KEY, endpoint, ALLOW_KEY, null))
                    .as("the endpoint '%s' carries no https scheme", endpoint)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void only_an_explicit_true_opts_out() {
        assertThat(Endpoints.secure(ENDPOINT_KEY, "http://localhost:9000", ALLOW_KEY, "true"))
                .hasToString("http://localhost:9000");
        assertThat(Endpoints.secure(ENDPOINT_KEY, "http://localhost:9000", ALLOW_KEY, "TRUE"))
                .hasToString("http://localhost:9000");
        // Anything that is not "true" leaves the screen on - the same Boolean.parseBoolean reading the store
        // uses, so a value that looks affirmative but is not ("1", "yes") does not quietly disable the guard.
        for (String value : new String[]{"", " ", "1", "yes", "on", "false", "no"}) {
            assertThatThrownBy(() -> Endpoints.secure(ENDPOINT_KEY, "http://localhost:9000", ALLOW_KEY, value))
                    .as("the opt-out value '%s' is not an opt-out", value)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void a_key_names_itself_in_both_the_spellings_an_operator_may_have_used() {
        assertThat(Endpoints.variable("jenreg.s3.endpoint")).isEqualTo("JENREG_S3_ENDPOINT");
        assertThat(Endpoints.variable("jenreg.gcs.allow-insecure-endpoint"))
                .isEqualTo("JENREG_GCS_ALLOW_INSECURE_ENDPOINT");
        assertThat(Endpoints.variable("jenreg.azure-blob.connection-string"))
                .isEqualTo("JENREG_AZURE_BLOB_CONNECTION_STRING");
    }
}
