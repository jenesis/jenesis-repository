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
 * The console's space is the closed one - a fixed set of screens - so the console declares it and the repository
 * keeps {@code anyRequest}.
 *
 * <p>That is the opposite of the intuitive reading, which is why it is written down. Handing the matcher to the
 * repository would mean enumerating a space that cannot be enumerated, and a path left out of it would not fail
 * loudly: it would fall through to the console's deny-by-default chain and answer an artifact download with a
 * redirect to {@code /login}.
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
     * <p>The framework ones belong here for the same reason as the screens: in a merged node they have to reach
     * the chain that knows how to authenticate a browser, not the one that expects an artifact key.
     */
    public static final List<String> PATTERNS = List.of(
            "/",
            "/catalog",
            "/posture",
            "/error",
            "/favicon.ico",
            "/login", "/login/**",
            "/logout",
            "/oauth2/**",
            "/css/**", "/js/**", "/assets/**", "/webjars/**",
            "/console", "/console/**",
            "/browse", "/browse/**");

    private ConsoleUrlSpace() {
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
