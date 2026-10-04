package build.jenesis.repository.test;

import module org.junit.jupiter.api;
import build.jenesis.repository.settings.ImportHostGuard;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The URL an operator submits for an import or an export, judged by the one screen every leg calls: https only, and
 * no host in a private, loopback or cloud-metadata range - both halves under the one
 * {@code block-private-import-hosts} dial.
 */
class ImportUrlRefusalTest {

    @Test
    void a_plaintext_submitted_url_is_refused_by_default_even_to_a_public_host() {
        // The private-host screen is silent about a public host, and an import attaches the operator's upstream
        // username and password.
        assertThat(ImportHostGuard.refusalReason("http://incumbent.example/", true))
                .isEqualTo("target is not https (scheme 'http')");
    }

    @Test
    void the_dial_is_the_whole_opt_out_and_covers_both_halves() {
        // One dial, deliberately: an operator able to permit cleartext but not internal hosts (or the reverse) is an
        // operator who can send a credential in the clear while the guard still reads as on.
        assertThat(ImportHostGuard.refusalReason("http://incumbent.example/", false)).isNull();
        assertThat(ImportHostGuard.refusalReason("http://127.0.0.1:8081/", false)).isNull();
    }

    @Test
    void a_submitted_url_at_an_internal_host_is_refused() {
        assertThat(ImportHostGuard.refusalReason("https://127.0.0.1:8081/", true))
                .isEqualTo("the host resolves to a private, loopback, link-local or cloud-metadata address");
    }

    @Test
    void the_transport_is_judged_first_so_a_plaintext_url_is_never_resolved_and_names_the_right_half() {
        // An operator whose source is plaintext on a public host must not be told to go and look at its host.
        assertThat(ImportHostGuard.refusalReason("http://127.0.0.1:8081/", true))
                .isEqualTo("target is not https (scheme 'http')");
    }

    @Test
    void an_unresolvable_host_stays_admissible_so_the_sources_own_probe_gives_the_better_message() {
        assertThat(ImportHostGuard.refusalReason("https://no-such-host.example/", true)).isNull();
    }

    @Test
    void a_malformed_or_schemeless_or_hostless_submitted_url_is_refused() {
        assertThat(ImportHostGuard.refusalReason("ht tp://incumbent.example/", true)).isEqualTo("the URL is malformed");
        assertThat(ImportHostGuard.refusalReason("incumbent.example/libs", true)).contains("is not https");
        assertThat(ImportHostGuard.refusalReason("file:///etc/passwd", true)).contains("is not https");
        assertThat(ImportHostGuard.refusalReason("https:///libs", true)).isEqualTo("the URL names no host");
    }
}
