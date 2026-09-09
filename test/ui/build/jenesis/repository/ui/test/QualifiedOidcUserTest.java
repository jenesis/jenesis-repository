package build.jenesis.repository.ui.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.ui.QualifiedOidcUser;
import build.jenesis.repository.ui.ProviderPrincipal;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OIDC principal re-keying that closed the coverage gap: {@link QualifiedOidcUser} reports the provider-qualified
 * id ({@code <provider>/<sub>}) as the principal name - the key tenants store members under - rather than the bare
 * {@code sub} the standard {@code DefaultOidcUser} would, while still delegating its authorities and claims to the
 * underlying id token. This is the re-keying {@code OidcPrincipalService} performs so {@code authentication.getName()}
 * matches a tenant membership id, and the guard against two providers' identical {@code sub}s colliding.
 */
public class QualifiedOidcUserTest {

    @Test
    void reportsTheQualifiedIdAsTheNameNotTheBareSub() {
        OidcIdToken idToken = new OidcIdToken("id-token-value", Instant.now(), Instant.now().plusSeconds(3600),
                Map.of("sub", "sub-123", "login", "octocat"));
        Set<GrantedAuthority> authorities = Set.of(new SimpleGrantedAuthority("ROLE_USER"));

        QualifiedOidcUser user = new QualifiedOidcUser(authorities, idToken, null, "oidc/sub-123");

        assertThat(user.getName()).as("the principal name is the qualified id, not the bare sub")
                .isEqualTo("oidc/sub-123");
        assertThat(user.getSubject()).as("the underlying id-token claim still reads through").isEqualTo("sub-123");
        assertThat(user.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_USER");
        assertThat(user.getIdToken().getTokenValue()).isEqualTo("id-token-value");
    }

    @Test
    void theCollisionGuardIsTheRegistrationQualifiedId() {
        // The re-keying is not a hand-set string: the qualified id the user is named by is derived from the
        // registration id and the raw sub by the very function {@code OidcPrincipalService} applies -
        // {@link Principals#qualifiedId} - so a change to the qualification scheme is caught here. The same raw
        // sub under two providers must therefore key to two distinct principals. (That the SERVICE actually applies
        // this derivation end to end - registration "oidc" + sub "sub-123" is admitted as "oidc/sub-123" - is
        // proven over a real authorization store in PrincipalServiceLoadUserTest; this pins the composition rule.)
        OidcIdToken idToken = new OidcIdToken("t", Instant.now(), Instant.now().plusSeconds(3600), Map.of("sub", "42"));
        Set<GrantedAuthority> roles = Set.of(new SimpleGrantedAuthority("ROLE_USER"));

        QualifiedOidcUser github = new QualifiedOidcUser(roles, idToken, null, ProviderPrincipal.qualifiedId("github", "42"));
        QualifiedOidcUser google = new QualifiedOidcUser(roles, idToken, null, ProviderPrincipal.qualifiedId("google", "42"));

        assertThat(github.getName()).as("the qualified id composes the registration id with the raw sub")
                .isEqualTo("github/42");
        assertThat(google.getName()).isEqualTo("google/42");
        assertThat(github.getName()).as("the same raw sub under different providers cannot collide")
                .isNotEqualTo(google.getName());
        assertThat(github.getSubject()).as("the bare sub still reads through, unqualified, under the collision key")
                .isEqualTo("42");
    }
}
