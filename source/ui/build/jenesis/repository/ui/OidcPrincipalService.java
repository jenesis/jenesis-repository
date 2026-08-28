package build.jenesis.repository.ui;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import module java.base;

/**
 * Turns an OpenID Connect sign-in into a console principal: the {@code sub} claim, qualified by the registration it
 * came from, with the authorities the deployment's {@link LoginAuthorities} policy grants it.
 *
 * <p>The policy is the only part that varies between deployments - one grants from a configured list of admins,
 * another from stored tenant memberships and refuses a principal that belongs to none - so it is the only part
 * injected. Everything here (load the user, derive the qualified id, carry it on the principal) is the same wherever
 * the console runs, which is why it is written once.
 */
public class OidcPrincipalService extends OidcUserService {

    private final LoginAuthorities authorities;

    public OidcPrincipalService(LoginAuthorities authorities) {
        this.authorities = authorities;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        OidcUser user = super.loadUser(request);
        String qualifiedId = ProviderPrincipal.qualifiedId(
                request.getClientRegistration().getRegistrationId(), user.getName());
        String displayName = ProviderPrincipal.displayName(user.getAttributes());
        Collection<GrantedAuthority> granted = authorities.authorities(qualifiedId, displayName);
        return new QualifiedOidcUser(granted, user.getIdToken(), user.getUserInfo(), qualifiedId);
    }
}
