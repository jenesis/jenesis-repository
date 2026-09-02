package build.jenesis.repository.store.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.store.Endpoints;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boundary coverage for the shared https-only transport screen {@link Endpoints#secure} in isolation, beside the
 * per-backend suites that pin each backend's own key spellings through it ({@code S3EndpointSchemeTest},
 * {@code GcsConditionalWriteTest}, {@code AzureEndpointSchemeTest}) and the store contract kit's
 * {@code PLAINTEXT_ENDPOINT_REFUSED} property that drives it through a real resolution.
 *
 * <p>The rule used to be written out once per backend, so a boundary a reviewer fixed in one copy - which
 * spellings of the opt-out count as {@code true}, whether an uppercase scheme passes - was silently a different rule
 * in the other two. Those boundaries are asserted here once, on the one implementation, so a change to them cannot be
 * partial.
 */
class EndpointScreenTest {

    private static final String ENDPOINT_KEY = "jenreg.s3.endpoint";

    private static final String ALLOW_KEY = "jenreg.s3.allow-insecure-endpoint";

    @Test
    void an_https_endpoint_needs_no_opt_out_whatever_its_case() {
        assertThat(Endpoints.secure(ENDPOINT_KEY, "https://s3.example.com", ALLOW_KEY, null))
                .isEqualTo(URI.create("https://s3.example.com"));
        assertThat(Endpoints.secure(ENDPOINT_KEY, "HTTPS://s3.example.com", ALLOW_KEY, null))
                .as("a scheme is case-insensitive per RFC 3986, so an uppercase one is the same secure transport")
                .isEqualTo(URI.create("HTTPS://s3.example.com"));
    }

    @Test
    void a_plaintext_endpoint_is_refused_naming_both_keys_and_the_value() {
        assertThatThrownBy(() -> Endpoints.secure(ENDPOINT_KEY, "http://localhost:9000", ALLOW_KEY, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ENDPOINT_KEY)
                .hasMessageContaining(ALLOW_KEY)
                .hasMessageContaining("http://localhost:9000")
                .hasMessageContaining("https");
    }

    @Test
    void every_scheme_that_is_not_https_is_refused_including_none_at_all() {
        for (String endpoint : List.of("http://localhost:9000", "ftp://files.example.com", "s3://bucket",
                "localhost:9000", "//localhost:9000")) {
            assertThatThrownBy(() -> Endpoints.secure(ENDPOINT_KEY, endpoint, ALLOW_KEY, null))
                    .as("'%s' is not an https transport, so it is refused unless the operator opts out", endpoint)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void the_opt_out_is_boolean_true_and_nothing_else() {
        assertThat(Endpoints.secure(ENDPOINT_KEY, "http://localhost:9000", ALLOW_KEY, "true"))
                .isEqualTo(URI.create("http://localhost:9000"));
        assertThat(Endpoints.secure(ENDPOINT_KEY, "http://localhost:9000", ALLOW_KEY, "TRUE"))
                .as("Boolean.parseBoolean is case-insensitive, so an operator's TRUE opts out too")
                .isEqualTo(URI.create("http://localhost:9000"));
        for (String value : List.of("", " ", "1", "yes", "on", "false", "no")) {
            assertThatThrownBy(() -> Endpoints.secure(ENDPOINT_KEY, "http://localhost:9000", ALLOW_KEY, value))
                    .as("'%s' is not 'true', so the screen stays on rather than guessing an intent", value)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void a_null_endpoint_has_no_scheme_to_judge_and_is_left_to_the_client() {
        // The azure-blob backend extracts its endpoint from a connection string that may declare none at all - a
        // shape the SDK itself refuses - so the screen answers null rather than inventing a second diagnostic.
        assertThat(Endpoints.secure(ENDPOINT_KEY, null, ALLOW_KEY, null)).isNull();
        assertThat(Endpoints.secure(ENDPOINT_KEY, null, ALLOW_KEY, "true")).isNull();
    }
}
