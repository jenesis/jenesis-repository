package build.jenesis.repository.ui.identity;

import module java.base;

import org.springframework.security.core.Authentication;

/**
 * What counts as the deployment's starter credential, settled once: a console session is on the starter credential
 * when it was authenticated by the environment's admin key ({@code jenreg.ui.admin-key}) - a secret a deployment is
 * provisioned with, granted super-admin over every tenant and re-provisioned on every boot for as long as it is
 * set, with no identity behind it. The key-login mechanism marks such a session with {@link #AUTHORITY}, and the
 * first-run setup screen is where a super-admin on it is sent.
 *
 * <p>Two other shapes are deliberately <em>not</em> the starter credential. An id seeded from
 * {@code jenreg.ui.admins} is a real identity - an SSO subject - whose deployment-wide grant happened to be
 * written from configuration; it is what the first setup step asks the operator to create, not what it asks them
 * to stop using, so a session on it is an ordinary super-admin session. And the API's bootstrap key
 * ({@code jenreg.bootstrap-key}) never signs into the console at all: the setup screen reports whether it is set,
 * from the environment, and says what to do about it.
 */
public final class StarterCredential {

    /** The authority a session authenticated by the environment's admin key carries, beside its roles. */
    public static final String AUTHORITY = "ROLE_STARTER_CREDENTIAL";

    private StarterCredential() {
    }

    /** Whether this session was authenticated by the starter credential. */
    public static boolean signedInWith(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> AUTHORITY.equals(authority.getAuthority()));
    }
}
