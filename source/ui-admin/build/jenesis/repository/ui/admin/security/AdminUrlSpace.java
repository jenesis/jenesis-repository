package build.jenesis.repository.ui.admin.security;

import module java.base;

import build.jenesis.repository.ui.ConsoleUrlSpace;

/**
 * The admin console's URL space: the base console's, plus the screens this one adds.
 *
 * <p>It composes rather than restates. The base console already declares the shell it shares - the root, the login
 * and logout paths, the static trees, {@code /console} and {@code /browse} - and restating those here would be two
 * lists to keep in step, which is how the four already-recorded copies in this tree began. What is genuinely
 * this console's own is what is listed below: tenancy, credentials, settings, the per-tenant admin area, users, the SCIM
 * token screen and the audit trail.
 *
 * <p>See {@link ConsoleUrlSpace} for why the console carries the {@code securityMatcher} and the repository does
 * not, and {@code ConsoleUrlSpaceCensusTest} for the check that keeps this list true.
 */
public final class AdminUrlSpace {

    /** What this console adds to the base console's shell. */
    private static final List<String> ADDITIONAL = List.of(
            "/saml2/**",
            "/repositories", "/repositories/**",
            "/projects", "/projects/**",
            "/credentials", "/credentials/**",
            "/instances", "/instances/**",
            "/setup", "/setup/**",
            "/settings", "/settings/**",
            "/admin", "/admin/**",
            "/users", "/users/**",
            "/scim-token", "/scim-token/**",
            "/audit", "/audit/**",
            "/license");

    /** The whole space this console serves, ready to hand to {@code securityMatcher}. */
    public static final List<String> PATTERNS = ConsoleUrlSpace.with(ADDITIONAL);

    private AdminUrlSpace() {
    }

    /** Whether a mapped route's pattern falls inside this console's space. */
    public static boolean covers(String pattern) {
        return ConsoleUrlSpace.covers(PATTERNS, pattern);
    }
}
