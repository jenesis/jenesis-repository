package build.jenesis.repository.ui;

import module java.base;


/**
 * Every path the console serves, declared once, so a node running the console alongside the repository can tell
 * their security chains apart.
 *
 * <p><b>Why the console carries the matcher and the repository does not.</b> Spring takes the first chain whose
 * matcher matches, so exactly one chain can be the unmatched fall-through - and it has to be the one whose URL
 * space cannot be enumerated. That is the repository: {@code /repository/**} carries arbitrary artifact
 * coordinates, {@code /v2/**} is the OCI data plane, and the format roots grow whenever a format is installed.
 * The console's space is the closed one - {@code /ui} and the sign-in endpoints - so the console declares it and
 * the repository keeps {@code anyRequest}.
 *
 * <p>That is the opposite of the intuitive reading, which is why it is written down. Handing the matcher to the
 * repository would mean enumerating a space that cannot be enumerated, and a path left out of it would not fail
 * loudly: it would fall through to the console's deny-by-default chain and answer an artifact download with a
 * redirect to the sign-in page.
 *
 * <p><b>An edition adds to this rather than replacing it.</b> The admin console serves these paths and more, so it
 * composes its own list on top through {@link #with}. Both then use {@link #covers} to decide membership, and both
 * are checked by a census against the routes their console actually maps - a declaration nothing checks stops
 * being true the first time somebody adds a screen.
 */
public final class ConsoleUrlSpace {

    /**
     * The console's own paths, plus the framework paths it must answer.
     *
     * <p>Every screen, form and asset the console serves is under {@code /ui}, whichever module contributes it, so
     * the console's space is that one subtree - beside the artifacts at {@code /repository}, the registry at
     * {@code /v2}, the build cache at {@code /build} and the API at {@code /api} - and the bare root, which forwards
     * to it. The framework paths belong here for the same reason as the screens: in a merged node they have to reach
     * the chain that knows how to authenticate a browser, not the one that expects an artifact key. They stay at the
     * root because an identity provider is configured with them: the OAuth2 authorization and callback endpoints
     * ({@code /oauth2/**}, {@code /login/oauth2/code/*}) and, where it is installed, SAML's
     * ({@code /login/saml2/sso/*}).
     */
    public static final List<String> PATTERNS = List.of(
            "/",
            "/ui", "/ui/**",
            "/error",
            "/favicon.ico",
            "/login/**",
            "/oauth2/**");

    /**
     * The subset a browser reaches before it has a principal: the probes a platform calls unauthenticated, the
     * sign-in pages and the endpoints an identity provider calls back, the error and favicon paths, and the
     * directories a console ships static files under.
     *
     * <p>Declared here because three chains permit it - this console's, its development profile's, and the admin
     * console's - and three copies of a list is three chances to leave something out.
     *
     * <p><b>A pattern belongs here only when a module ships files under it.</b> Being in {@link #PATTERNS} says the
     * console's chain governs a path, and being here says no principal is required, which is a different and much
     * stronger claim. The census over shipped resource directories finds exactly four.
     *
     * <p>{@code POST /ui/logout} is not in the list: it is permitted by method as well as path, so a chain spells it
     * out on its own line.
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
     * Whether {@code pattern} - a mapped route's pattern, which may itself contain path variables - falls inside
     * {@code space}.
     *
     * <p>The comparison is on the literal prefix before any variable, because a route pattern like
     * {@code /repositories/{repo}/browse} is not a request path and cannot be matched as one. A pattern that is
     * entirely a variable or a wildcard at the root is refused rather than silently passed: it would claim the
     * whole space, which is the shortcut this check exists to prevent.
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
