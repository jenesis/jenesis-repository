package build.jenesis.repository.ui.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.ui.GithubCredentials;
import build.jenesis.repository.ui.LiveClientRegistrations;
import org.springframework.security.oauth2.client.registration.ClientRegistration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GitHub sign-in follows its credentials as they are when a sign-in starts: none while no client id is configured, and
 * the app saved since - from the console's first-run guide, say - at once, with no restart between the paste and the
 * click. The sign-in page lists what is configured at the moment it renders.
 */
class LiveClientRegistrationsTest {

    @Test
    void a_client_id_saved_since_signs_in_without_a_restart() {
        AtomicReference<String> clientId = new AtomicReference<>("");
        LiveClientRegistrations registrations = new LiveClientRegistrations(new GithubCredentials() {
            @Override
            public String clientId() {
                return clientId.get();
            }

            @Override
            public String clientSecret() {
                return "the-secret";
            }
        }, Optional.empty());

        assertThat(registrations.findByRegistrationId("github")).as("no app configured, no GitHub sign-in").isNull();
        assertThat(registrations).isEmpty();

        clientId.set("Iv1.0123456789abcdef");

        ClientRegistration github = registrations.findByRegistrationId("github");
        assertThat(github).as("the app saved since is the one a sign-in uses").isNotNull();
        assertThat(github.getClientId()).isEqualTo("Iv1.0123456789abcdef");
        assertThat(github.getClientSecret()).isEqualTo("the-secret");
        assertThat(registrations).extracting(ClientRegistration::getRegistrationId).containsExactly("github");
    }
}
