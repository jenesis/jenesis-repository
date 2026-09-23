package build.jenesis.repository.ui;

import module java.base;

import build.jenesis.repository.observation.Contributions;

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
            "/observability",
            "/error",
            "/favicon.ico",
            "/login", "/login/**",
            "/logout",
            "/no-access",
            "/oauth2/**",
            "/css/**", "/js/**", "/img/**", "/webjars/**",
            "/console", "/console/**");

    /**
     * The subset a browser reaches before it has a principal: the probes a platform calls unauthenticated, the
     * sign-in and token-exchange endpoints, the error and favicon paths, and the directories a console ships
     * static files under.
     *
     * <p>Declared here because three chains permit it - this console's, its development profile's, and the admin
     * console's - and three copies of a list is three chances to leave something out. They had: {@code /oauth2/**}
     * and {@code /login/**} were permitted by one, two and one of them respectively, and {@code /img/**} by none,
     * although the brand fragment every page renders asks for two files under it.
     *
     * <p><b>A pattern belongs here only when a module ships files under it.</b> This list briefly carried
     * {@code /assets/**} too, on the reasoning that an asset directory protects nothing - but {@code /assets} was
     * not a directory, it was a console export of what the store holds, and permitting it handed an anonymous
     * caller the repository's contents. That export is gone with the deployment-wide browse it belonged to, and
     * the enumeration it offered is the repository's own {@code /api/assets}, which is scoped and key-gated. The
     * lesson survives the route: being in {@link #PATTERNS} says the console's chain governs a path, and being
     * here says no principal is required, which is a different and much stronger claim. The census over shipped
     * resource directories finds exactly three.
     *
     * <p>{@code POST /logout} is not in the list: it is permitted by method as well as path, so a chain spells it
     * out on its own line.
     */
    public static final List<String> ANONYMOUS = List.of(
            "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness",
            "/login", "/login/**", "/oauth2/**",
            "/error", "/favicon.ico",
            "/css/**", "/js/**", "/img/**");

    private ConsoleUrlSpace() {
    }

    /**
     * The paths installed console modules contribute, taken from the nav entries they declare.
     *
     * <p>A module contributed through {@link ConsoleModuleProvider} registers screens, and a screen is a path the
     * console must answer - so it has to be inside the console's chain, or it falls through to the repository's and
     * an operator's screen answers {@code 401} to a browser. The module already declares that path, in the
     * {@link NavEntry} it contributes; taking it from there is what keeps the two from being two.
     *
     * <p>They used to be. Every module's paths were also written into an edition's own list by hand - the SCIM
     * module's {@code /scim-token} among them - which is a declaration restated in a place that cannot see the
     * original. The restatement is what drifts, and it drifts silently: a module whose path nobody copied is
     * installed, discovered, mapped, and unreachable.
     *
     * <p>Each entry contributes its exact path and the subtree beneath it, because a screen that posts to
     * {@code /deploy} today will have {@code /deploy/something} tomorrow and the module is not going to be told to
     * come back here. A module that is installed but switched off contributes its path too: nothing maps it, so it
     * is a {@code 404} from the console rather than a {@code 401} from the repository, which is the honest answer
     * for a screen that is not installed.
     */
    public static List<String> contributed() {
        List<String> paths = new ArrayList<>();
        for (ConsoleModuleProvider module : ConsoleModuleProvider.installed()) {
            // A module whose nav declaration throws contributes no paths rather than taking the console down with
            // it. This runs while the security chain is being built, which is the worst possible place for an
            // uncontained fan-out: one broken plugin would fail the context and there would be no console at all,
            // rather than one screen that cannot be reached.
            for (NavEntry entry : Contributions.declared(module, ConsoleModuleProvider::navEntries,
                    List.<NavEntry>of())) {
                String path = entry.path();
                if (path == null || !path.startsWith("/") || path.equals("/")) {
                    continue;
                }
                paths.add(path);
                paths.add(path.endsWith("/") ? path + "**" : path + "/**");
            }
        }
        return List.copyOf(paths);
    }

    /** This console's own space plus what the installed modules contribute - what a console with no edition of its
     *  own hands to {@code securityMatcher}. */
    public static List<String> space() {
        return with(List.of());
    }

    /** This space plus an edition's own paths and the installed modules' contributions, in one list ready for
     *  {@code securityMatcher}. */
    public static List<String> with(List<String> additional) {
        List<String> composed = new ArrayList<>(PATTERNS);
        composed.addAll(additional);
        composed.addAll(contributed());
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
