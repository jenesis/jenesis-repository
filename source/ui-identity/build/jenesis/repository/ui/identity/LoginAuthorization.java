package build.jenesis.repository.ui.identity;

import module java.base;

import build.jenesis.repository.ui.SuperadminRole;
import build.jenesis.repository.ui.KnownPrincipals;
import build.jenesis.repository.ui.LoginAuthorities;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The provider-independent login decision shared by the OAuth2, OIDC and SAML mechanisms. Authorities are coarse -
 * {@code ROLE_USER} for everyone, plus {@code ROLE_SUPERADMIN} - because the meaningful role is per tenant and resolved
 * per request.
 *
 * <h2>Sign-in does not refuse</h2>
 * Refusing a principal who is no tenant's member would duplicate the provider's own control (app assignment in Entra or
 * Okta, an OAuth app scoped to one organisation) while seeing less, and would make granting access nearly impossible:
 * an administrator grants to an opaque {@code oidc/<sub>} the deployment would then never see.
 *
 * <p>Membership is authorization, not authentication: {@code MembershipConsoleAccess} answers a principal holding
 * nothing with a screen saying so and showing its id, and what a principal may reach is decided by the membership read
 * on every request.
 */
public class LoginAuthorization implements LoginAuthorities {

    private final Superadmins superadmins;
    private final KnownPrincipals known;

    public LoginAuthorization(Superadmins superadmins, KnownPrincipals known) {
        this.superadmins = superadmins;
        this.known = known;
    }

    @Override
    public Set<GrantedAuthority> authorities(String qualifiedId, String login) {
        // The one moment this deployment learns a person's provider subject: recorded so an administrator can grant
        // them a tenant from a list rather than from an opaque string passed out of band.
        known.record(qualifiedId, login);
        boolean superadmin = superadmins.is(qualifiedId);
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        if (superadmin) {
            authorities.add(new SimpleGrantedAuthority(SuperadminRole.AUTHORITY));
        }
        return authorities;
    }
}
