package build.jenesis.repository.ui;

import module java.base;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

/**
 * An OIDC user whose name is the provider-qualified id rather than the bare {@code sub} claim, so audit rows, membership
 * lookups and displays use the identity the authorization policy was asked about.
 */
public class QualifiedOidcUser extends DefaultOidcUser {

    private final String name;

    public QualifiedOidcUser(Collection<? extends GrantedAuthority> authorities,
                             OidcIdToken idToken, OidcUserInfo userInfo, String name) {
        super(authorities, idToken, userInfo);
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }
}
