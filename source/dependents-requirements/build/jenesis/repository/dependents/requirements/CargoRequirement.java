package build.jenesis.repository.dependents.requirements;

import module java.base;

/**
 * Cargo's requirement grammar, translated into the node-semver range it means and evaluated as npm's is.
 *
 * <p>The two differ in spelling rather than meaning: Cargo separates comparators with commas where npm uses spaces,
 * and a bare {@code 1.2.3} in a {@code Cargo.toml} is a caret requirement where npm reads it as exactly that version.
 * Caret, tilde, wildcard and comparison operators mean the same in both. Cargo has no {@code ||} union and no hyphen
 * range, so a requirement carrying either is not one Cargo wrote and answers {@link Requirements.Verdict#UNKNOWN}, as
 * does a comparator that is not an operator and a version.
 */
final class CargoRequirement implements Requirements.Grammar {

    private static final Pattern COMPARATOR = Pattern.compile("(\\^|~|>=|<=|>|<|=)?\\s*([0-9*][0-9A-Za-z.*+\\-]*)");

    @Override
    public Requirements.Verdict admits(String requirement, String version) {
        String range = translate(requirement);
        return range == null ? Requirements.Verdict.UNKNOWN : NpmRequirement.evaluate(range, version);
    }

    /** The node-semver range a Cargo requirement means, or {@code null} for one this cannot read. */
    static String translate(String requirement) {
        if (requirement.contains("|") || requirement.contains(" - ")) {
            return null;                                        // no union and no hyphen range in Cargo's grammar
        }
        StringJoiner range = new StringJoiner(" ");
        for (String comparator : requirement.split(",")) {
            Matcher matcher = COMPARATOR.matcher(comparator.trim());
            if (!matcher.matches()) {
                return null;
            }
            String operator = matcher.group(1);
            String bound = matcher.group(2);
            // A bare version is a caret requirement; a bare wildcard is itself.
            range.add((operator == null ? (bound.contains("*") ? "" : "^") : operator) + bound);
        }
        return range.toString();
    }
}
