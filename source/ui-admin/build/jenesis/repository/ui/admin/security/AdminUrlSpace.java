package build.jenesis.repository.ui.admin.security;

import module java.base;

import build.jenesis.repository.ui.ConsoleUrlSpace;

/**
 * The admin console's URL space: the base console's, plus the screens this one adds.
 *
 * <p>It composes rather than restates. The base console already declares the space it shares - {@code /ui}, where
 * every screen of either console lives, and the sign-in endpoints - and restating those here would be two lists to
 * keep in step. What is genuinely this console's own is the SAML endpoints, below.
 *
 * <p>See {@link ConsoleUrlSpace} for why the console carries the {@code securityMatcher} and the repository does
 * not, and {@code ConsoleUrlSpaceCensusTest} for the check that keeps this list true.
 */
public final class AdminUrlSpace {

    /** What this console adds to the base console's shell: the SAML endpoints an identity provider is configured
     *  with, where the SAML module is installed. Every screen is under {@code /ui} already. */
    private static final List<String> ADDITIONAL = List.of("/saml2/**");

    /** The whole space this console serves, ready to hand to {@code securityMatcher}. */
    public static final List<String> PATTERNS = ConsoleUrlSpace.with(ADDITIONAL);

    private AdminUrlSpace() {
    }

    /** Whether a mapped route's pattern falls inside this console's space. */
    public static boolean covers(String pattern) {
        return ConsoleUrlSpace.covers(PATTERNS, pattern);
    }
}
