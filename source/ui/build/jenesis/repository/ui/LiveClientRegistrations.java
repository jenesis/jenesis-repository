package build.jenesis.repository.ui;

import module java.base;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * The sign-in providers as they stand when a sign-in starts: GitHub from its credentials as they are now, so a client
 * id pasted into the console signs in without a restart, and the OpenID Connect provider discovered once at boot from
 * its issuer. Iterable, so the sign-in page lists what is configured at the moment it renders.
 */
public final class LiveClientRegistrations implements ClientRegistrationRepository, Iterable<ClientRegistration> {

    private final GithubCredentials github;
    private final Optional<ClientRegistration> oidc;

    public LiveClientRegistrations(GithubCredentials github, Optional<ClientRegistration> oidc) {
        this.github = github;
        this.oidc = oidc;
    }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        return registrations().filter(registration -> registration.getRegistrationId().equals(registrationId))
                .findFirst().orElse(null);
    }

    @Override
    public Iterator<ClientRegistration> iterator() {
        return registrations().iterator();
    }

    private Stream<ClientRegistration> registrations() {
        return Stream.concat(ConsoleClientRegistrations.github(github.clientId(), github.clientSecret()).stream(),
                oidc.stream());
    }
}
