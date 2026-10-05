package build.jenesis.repository.dependents.requirements;

import module java.base;
import org.semver4j.Semver;

/**
 * npm's requirement grammar, read by semver4j's implementation of node-semver ranges: carets, tildes, x-ranges,
 * hyphen ranges and {@code ||} unions.
 *
 * <p>A dependency value in {@code package.json} is not always a range. A dist-tag ({@code latest}, {@code next}),
 * a URL, a git, file or workspace source and an {@code npm:} alias all name something no version comparison decides,
 * and the library reads some of them as the widest range there is - {@code latest} admits everything to it. So
 * anything that is not plainly a range answers {@link Requirements.Verdict#UNKNOWN} before the library is asked:
 * a value carrying a colon or a slash is a source, and a word with no digit that is not a wildcard or an operator is
 * a tag, whether it stands alone or beside a range.
 */
final class NpmRequirement implements Requirements.Grammar {

    private static final Set<String> WILDCARDS = Set.of("*", "x", "X");

    @Override
    public Requirements.Verdict admits(String requirement, String version) {
        if (!range(requirement)) {
            return Requirements.Verdict.UNKNOWN;
        }
        return evaluate(requirement, version);
    }

    /** Whether the node-semver range {@code range} admits {@code version} - the one evaluation the grammars that
     *  translate into node-semver share, so a version one of them cannot parse answers unknown the same way. */
    static Requirements.Verdict evaluate(String range, String version) {
        try {
            Semver parsed = Semver.parse(version);
            if (parsed == null) {
                return Requirements.Verdict.UNKNOWN;           // not a semantic version npm could have installed
            }
            return parsed.satisfies(range) ? Requirements.Verdict.ADMITS : Requirements.Verdict.EXCLUDES;
        } catch (RuntimeException unreadable) {
            return Requirements.Verdict.UNKNOWN;
        }
    }

    /** Whether every token of the requirement is a piece of a range: a version or partial version (it carries a
     *  digit), a wildcard, the hyphen of a hyphen range, or an operator written apart from its version. */
    private static boolean range(String requirement) {
        if (requirement.indexOf(':') >= 0 || requirement.indexOf('/') >= 0) {
            return false;                                       // a URL, a git/file/workspace source, an alias
        }
        for (String token : requirement.split("\\|\\||\\s+")) {
            if (!token.isEmpty() && !WILDCARDS.contains(token) && !token.equals("-") && !OPERATOR.matcher(token).matches()
                    && token.chars().noneMatch(Character::isDigit)) {
                return false;                                   // a dist-tag, alone or beside a range
            }
        }
        return true;
    }

    private static final Pattern OPERATOR = Pattern.compile("[<>=~^]+");
}
