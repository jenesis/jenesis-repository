package build.jenesis.repository.ui.identity;

import module java.base;

import org.springframework.security.core.Authentication;

/**
 * What counts as the deployment's starter credential: a session authenticated by the environment's admin key
 * ({@code jenrepo.ui.admin-key}) - a provisioned secret granted super-admin over every tenant, with no identity behind
 * it. The key-login mechanism marks such a session with {@link #AUTHORITY}, and the first-run setup screen is where a
 * super-admin on it is sent.
 *
 * <p>Not the starter credential: an id seeded from {@code jenrepo.ui.admins} is a real SSO identity whose grant came
 * from configuration - what setup asks the operator to create - and the API's {@code jenrepo.bootstrap-key} never signs
 * into the console; the setup screen reports whether it is set.
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
