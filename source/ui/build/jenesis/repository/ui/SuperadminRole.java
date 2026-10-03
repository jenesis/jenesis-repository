package build.jenesis.repository.ui;

import module java.base;

import org.springframework.security.core.Authentication;

/**
 * The deployment's administrator role: granted at sign-in to a principal the deployment administers through, required
 * by the routes only a super-admin reaches, and asked about by every screen that answers a super-admin differently.
 */
public final class SuperadminRole {

    /** The role's name, as a route's {@code hasRole} names it. */
    public static final String ROLE = "SUPERADMIN";

    /** The authority a super-admin's sign-in holds. */
    public static final String AUTHORITY = "ROLE_" + ROLE;

    private SuperadminRole() {
    }

    /** Whether {@code authentication} - which may be absent - holds the role. */
    public static boolean held(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> AUTHORITY.equals(authority.getAuthority()));
    }
}
