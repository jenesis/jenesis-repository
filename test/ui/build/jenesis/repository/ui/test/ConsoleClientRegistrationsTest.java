package build.jenesis.repository.ui.test;

import build.jenesis.repository.ui.ConsoleClientRegistrations;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console's client registrations: which providers exist for a given configuration, and - the load-bearing half -
 * which do not.
 *
 * <p>An unconfigured provider must produce no registration <em>and reach nothing</em>. Discovery fetches the
 * issuer's document over the network, so a builder that ran it before checking whether an issuer was configured
 * would make every console without SSO do a boot-time HTTP call to whatever an empty or half-set property happened
 * to contain, and fail to start when it did not answer. These cases therefore assert the empty result without any
 * stub server standing by: if the short-circuit is ever removed, they fail by trying to resolve nothing.
 */
class ConsoleClientRegistrationsTest {

    @Test
    void github_is_built_only_once_a_client_id_is_configured() {
        assertThat(ConsoleClientRegistrations.github("", "secret")).isEmpty();
        assertThat(ConsoleClientRegistrations.github("   ", "secret")).as("blank is unconfigured").isEmpty();
        assertThat(ConsoleClientRegistrations.github(null, "secret")).isEmpty();

        Optional<ClientRegistration> configured = ConsoleClientRegistrations.github("  id  ", "  secret  ");
        assertThat(configured).isPresent();
        assertThat(configured.get().getRegistrationId()).isEqualTo("github");
        assertThat(configured.get().getClientId()).as("trimmed, so a copy-pasted value with spaces still works")
                .isEqualTo("id");
        assertThat(configured.get().getClientSecret()).isEqualTo("secret");
        assertThat(configured.get().getScopes()).containsExactly("read:user");
    }

    @Test
    void oidc_needs_both_an_issuer_and_a_client_id_before_it_resolves_anything() {
        // No stub issuer is running. Each of these must answer empty without attempting discovery - which is also
        // the only reason this test can make the assertion at all.
        List<String> scopes = List.of("openid");
        assertThat(ConsoleClientRegistrations.oidc("", "client", "secret", "Name", scopes)).isEmpty();
        assertThat(ConsoleClientRegistrations.oidc("   ", "client", "secret", "Name", scopes)).isEmpty();
        assertThat(ConsoleClientRegistrations.oidc(null, "client", "secret", "Name", scopes)).isEmpty();
        assertThat(ConsoleClientRegistrations.oidc("https://issuer.example", "", "secret", "Name", scopes)).isEmpty();
        assertThat(ConsoleClientRegistrations.oidc("https://issuer.example", null, "secret", "Name", scopes))
                .as("an issuer with no client id identifies nobody, so there is nothing to discover for")
                .isEmpty();
    }

    @Test
    void a_missing_secret_is_not_the_same_as_a_missing_client() {
        // A public client legitimately has no secret; that must not be read as "unconfigured" and silently drop the
        // provider, which would leave a console with no way in and no message saying why.
        assertThat(ConsoleClientRegistrations.github("id", null)).isPresent();
        assertThat(ConsoleClientRegistrations.github("id", null).orElseThrow().getClientSecret()).isEmpty();
    }
}
