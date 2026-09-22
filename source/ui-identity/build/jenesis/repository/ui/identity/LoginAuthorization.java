package build.jenesis.repository.ui.identity;

import module java.base;

import build.jenesis.repository.ui.KnownPrincipals;
import build.jenesis.repository.ui.LoginAuthorities;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The provider-independent login decision, shared by the OAuth2, OIDC and SAML sign-in mechanisms. Authorities are
 * coarse - {@code ROLE_USER} for everyone, plus {@code ROLE_SUPERADMIN} for super-admins - because the meaningful
 * role (viewer/editor/admin) is per tenant and resolved per request against the selected tenant.
 *
 * <h2>Sign-in no longer refuses</h2>
 * It used to throw for anyone who was a member of no tenant and not a super-admin, so a colleague the identity
 * provider had authenticated was told their account "belongs to no Jenesis tenant". That was wrong in two ways at
 * once. It duplicated a control the provider already owns - app assignment in Entra or Okta, an OAuth app scoped to
 * one organisation - while doing it worse, since this code only ever sees an identity the provider has already
 * decided about. And it made granting access to a new person nearly impossible: the id an administrator has to
 * grant to is an opaque {@code oidc/<sub>}, and under a refusal the deployment never saw it, so nobody could learn
 * what to type.
 *
 * <p>The predicate did not go away - it moved from authentication to authorization, as
 * {@code MembershipConsoleAccess}, where a principal that holds nothing is answered with a screen saying so and
 * carrying its own id. Nothing became more permissive: what a signed-in principal may reach is decided by exactly
 * the same membership read, one layer later and on every request rather than once per session.
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
        // The one moment this deployment learns a person's provider subject. Recording it is what lets an
        // administrator grant them a tenant from a list instead of being told an opaque string out of band - and
        // it is the half of the removed refusal that makes the removal useful rather than merely permissive.
        known.record(qualifiedId, login);
        boolean superadmin = superadmins.is(qualifiedId);
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        if (superadmin) {
            authorities.add(new SimpleGrantedAuthority("ROLE_SUPERADMIN"));
        }
        return authorities;
    }
}
