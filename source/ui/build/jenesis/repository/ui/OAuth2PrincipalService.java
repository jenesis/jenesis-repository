package build.jenesis.repository.ui;

import module java.base;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * Turns a plain OAuth2 sign-in - GitHub, which is not OIDC - into a console principal: the provider's user-name
 * attribute, qualified by the registration it came from, with the authorities the deployment's
 * {@link LoginAuthorities} policy grants it.
 *
 * <p>The qualified id is put into the attributes and made the name attribute, so the principal reports the same
 * identity the policy was asked about. Without that the name would be the provider's raw id, which is unique only
 * within that provider and is not what any grant is keyed by.
 */
public class OAuth2PrincipalService extends DefaultOAuth2UserService {

    /** The attribute the qualified id is carried in, and the name attribute of the resulting principal. */
    public static final String PRINCIPAL = "principal";

    private final LoginAuthorities authorities;

    public OAuth2PrincipalService(LoginAuthorities authorities) {
        this.authorities = authorities;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        OAuth2User user = super.loadUser(request);
        String qualifiedId = ProviderPrincipal.qualifiedId(
                request.getClientRegistration().getRegistrationId(), user.getName());
        String displayName = ProviderPrincipal.displayName(user.getAttributes());
        Collection<GrantedAuthority> granted = authorities.authorities(qualifiedId, displayName);
        Map<String, Object> attributes = new HashMap<>(user.getAttributes());
        attributes.put(PRINCIPAL, qualifiedId);
        return new DefaultOAuth2User(granted, attributes, PRINCIPAL);
    }
}
