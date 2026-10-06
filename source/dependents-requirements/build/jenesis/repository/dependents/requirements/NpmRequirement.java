package build.jenesis.repository.dependents.requirements;

import module java.base;
import build.jenesis.repository.closure.RequirementGrammar;
import org.semver4j.Semver;

/**
 * npm's requirement grammar, read by semver4j's implementation of node-semver ranges: carets, tildes, x-ranges,
 * hyphen ranges and {@code ||} unions.
 *
 * <p>A dependency value in {@code package.json} is not always a range. A dist-tag ({@code latest}, {@code next}),
 * a URL, a git, file or workspace source and an {@code npm:} alias all name something no version comparison decides,
 * and the library reads some of them as the widest range there is - {@code latest} admits everything to it. So
 * anything that is not plainly a range answers {@link RequirementGrammar.Admission#UNKNOWN} before the library is asked:
 * a value carrying a colon or a slash is a source, and a word with no digit that is not a wildcard or an operator is
 * a tag, whether it stands alone or beside a range.
 */
final class NpmRequirement implements ClosureGrammar.Reader {

    private static final Set<String> WILDCARDS = Set.of("*", "x", "X");

    @Override
    public RequirementGrammar.Admission admits(String requirement, String version) {
        if (!range(requirement)) {
            return RequirementGrammar.Admission.UNKNOWN;
        }
        return evaluate(requirement, version);
    }

    /** Whether the node-semver range {@code range} admits {@code version} - the one evaluation the grammars that
     *  translate into node-semver share, so a version one of them cannot parse answers unknown the same way. */
    static RequirementGrammar.Admission evaluate(String range, String version) {
        try {
            Semver parsed = Semver.parse(version);
            if (parsed == null) {
                return RequirementGrammar.Admission.UNKNOWN;           // not a semantic version npm could have installed
            }
            return parsed.satisfies(range) ? RequirementGrammar.Admission.ADMITS : RequirementGrammar.Admission.EXCLUDES;
        } catch (RuntimeException unreadable) {
            return RequirementGrammar.Admission.UNKNOWN;
        }
    }

    /** Whether every token of the requirement is a piece of a range: a version or partial version (it carries a
     *  digit), a wildcard, the hyphen of a hyphen range, or an operator written apart from its version. */
    static boolean range(String requirement) {
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
