package build.jenesis.repository.ui;

import module java.base;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

/**
 * An OIDC user whose name is the provider-qualified id rather than the bare {@code sub} claim.
 *
 * <p>Everything downstream - an audit row, a membership lookup, a display of who is signed in - wants the same
 * identity the authorization policy was asked about. Leaving the name as the raw subject means each of those either
 * re-derives the qualified form or, worse, does not, and keys itself by an id that is only unique within one
 * provider.
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
