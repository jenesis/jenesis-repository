package build.jenesis.repository.ui;

import module java.base;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Turns an OpenID Connect sign-in into a console principal: the qualified {@code sub} claim, with the authorities the
 * deployment's {@link LoginAuthorities} policy grants, the one part injected.
 */
public class OidcPrincipalService extends OidcUserService {

    private final LoginAuthorities authorities;
    private final ConsoleAdministrators administrators;

    public OidcPrincipalService(LoginAuthorities authorities) {
        this(authorities, null);
    }

    /** With {@code administrators}, which a pending {@link AdministratorClaim} is redeemed against; {@code null} where
     *  the console grants no administration. */
    public OidcPrincipalService(LoginAuthorities authorities, ConsoleAdministrators administrators) {
        this.authorities = authorities;
        this.administrators = administrators;
        // The user-info read goes over the product's own client, as every other call to the provider does.
        DefaultOAuth2UserService userInfo = new DefaultOAuth2UserService();
        userInfo.setRestOperations(ProviderRequests.rest());
        setOauth2UserService(userInfo);
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        OidcUser user = super.loadUser(request);
        String qualifiedId = ProviderPrincipal.qualifiedId(
                request.getClientRegistration().getRegistrationId(), user.getName());
        String displayName = ProviderPrincipal.displayName(user.getAttributes());
        // A claim the first-run guide made is granted before the rights are worked out, so this sign-in holds them.
        if (administrators != null) {
            AdministratorClaim.redeem(administrators, qualifiedId, Instant.now());
        }
        Collection<GrantedAuthority> granted = authorities.authorities(qualifiedId, displayName);
        return new QualifiedOidcUser(granted, user.getIdToken(), user.getUserInfo(), qualifiedId);
    }
}
