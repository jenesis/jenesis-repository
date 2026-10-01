package build.jenesis.repository.ui;

import module java.base;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * Turns a plain OAuth2 sign-in (GitHub) into a console principal with the authorities the deployment's
 * {@link LoginAuthorities} grants. The qualified id is made the name attribute, so the principal reports the identity
 * grants are keyed by rather than the provider's raw id.
 */
public class OAuth2PrincipalService extends DefaultOAuth2UserService {

    /** The attribute the qualified id is carried in, and the name attribute of the resulting principal. */
    public static final String PRINCIPAL = "principal";

    private final LoginAuthorities authorities;

    public OAuth2PrincipalService(LoginAuthorities authorities) {
        this.authorities = authorities;
        setRestOperations(ProviderRequests.rest());
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
