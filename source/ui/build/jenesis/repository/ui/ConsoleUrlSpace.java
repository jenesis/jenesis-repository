package build.jenesis.repository.ui;

import module java.base;


/**
 * Every path the console serves, declared once, so a node running the console beside the repository can tell their
 * security chains apart. Spring takes the first chain that matches, so only one chain can be the unmatched
 * fall-through, and it must be the one whose space cannot be enumerated: the repository's, whose formats add roots.
 * The console's space is closed, so the console declares it; a repository path missing from an enumerated list would
 * answer a download with a sign-in redirect.
 *
 * <p>The admin console composes its own list on top through {@link #with}; both use {@link #covers}, and a census
 * holds each list to the routes its console maps.
 */
public final class ConsoleUrlSpace {

    /**
     * The console's own paths: every screen, form and asset under {@code /ui}, the bare root that forwards there, and
     * the framework paths that must reach the browser-authenticating chain. Those stay at the root because an identity
     * provider is configured with them ({@code /oauth2/**}, {@code /login/oauth2/code/*}, SAML's
     * {@code /login/saml2/sso/*}).
     */
    public static final List<String> PATTERNS = List.of(
            "/",
            "/ui", "/ui/**",
            "/error",
            "/favicon.ico",
            "/login/**",
            "/oauth2/**");

    /**
     * The subset reached without a principal - health probes, sign-in pages and identity-provider callbacks, the error
     * and favicon paths, and the directories static files ship under - shared by the three chains that permit it. A
     * static directory belongs here only when a module ships files under it. {@code POST /ui/logout} is permitted by
     * method as well, so a chain states it separately.
     */
    public static final List<String> ANONYMOUS = List.of(
            "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness",
            "/ui/login", "/ui/login/**", "/login/**", "/oauth2/**",
            "/error", "/favicon.ico",
            "/ui/css/**", "/ui/js/**", "/ui/img/**", "/ui/fonts/**");

    private ConsoleUrlSpace() {
    }

    /** This console's own space - what a console with no edition of its own hands to {@code securityMatcher}. */
    public static List<String> space() {
        return PATTERNS;
    }

    /** This space plus an edition's own paths, in one list ready for {@code securityMatcher}. */
    public static List<String> with(List<String> additional) {
        List<String> composed = new ArrayList<>(PATTERNS);
        composed.addAll(additional);
        return List.copyOf(composed);
    }

    /**
     * Whether {@code pattern}, a mapped route's pattern, falls inside {@code space}, compared on its literal prefix
     * before any variable. A pattern that is a variable or wildcard at the root is refused, since it would claim the
     * whole space.
     */
    public static boolean covers(List<String> space, String pattern) {
        if (pattern.startsWith("/{") || pattern.equals("/**")) {
            return false;
        }
        String literal = literalPrefix(pattern);
        for (String declared : space) {
            if (declared.endsWith("/**")) {
                String prefix = declared.substring(0, declared.length() - 3);
                if (literal.equals(prefix) || literal.startsWith(prefix + "/")) {
                    return true;
                }
            } else if (declared.equals(literal)) {
                return true;
            }
        }
        return false;
    }

    /** The part of a route pattern before its first variable or wildcard - the most of it that is a real path. */
    private static String literalPrefix(String pattern) {
        int variable = pattern.indexOf('{');
        int wildcard = pattern.indexOf('*');
        int cut = variable < 0 ? wildcard : wildcard < 0 ? variable : Math.min(variable, wildcard);
        if (cut < 0) {
            return pattern;
        }
        String head = pattern.substring(0, cut);
        return head.endsWith("/") && head.length() > 1 ? head.substring(0, head.length() - 1) : head;
    }
}
