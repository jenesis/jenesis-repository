package build.jenesis.repository.dependents.requirements;

import module java.base;
import build.jenesis.repository.closure.spi.RequirementGrammar;

/**
 * Composer's constraint grammar, translated into the node-semver range it means and evaluated as npm's is.
 *
 * <p>Most of it reads the same: caret, comparison operators, wildcards, hyphen ranges and {@code ||}. Three places
 * differ, and the translation is there for them. A bare version is exact in Composer however short it is - {@code 1.2}
 * means {@code 1.2.0}, where npm reads it as every {@code 1.2.x}. A tilde with two parts is a floor up to the next
 * major - {@code ~1.2} is {@code >=1.2.0 <2.0.0}, where npm's stops below {@code 1.3.0}. And a single {@code |}, or a
 * comma between comparators, is Composer's own spelling of the union and the intersection.
 *
 * <p>What has no node-semver meaning answers {@link RequirementGrammar.Admission#UNKNOWN} rather than something near it: a
 * stability flag ({@code @dev}), a branch ({@code dev-main}), a not-equal ({@code !=}), a version of four parts, and a
 * platform requirement's name is never asked about. A {@code v} before a version is dropped on both sides, as
 * Composer's own normalisation does.
 */
final class ComposerRequirement implements ClosureGrammar.Reader {

    private static final Pattern COMPARATOR = Pattern.compile("(\\^|~|>=|<=|>|<|==|=)?v?([0-9][0-9.*xX]*)");

    private static final Pattern HYPHEN = Pattern.compile("v?([0-9][0-9.]*)\\s+-\\s+v?([0-9][0-9.]*)");

    @Override
    public RequirementGrammar.Admission admits(String requirement, String version) {
        String range = translate(requirement);
        String asked = version.startsWith("v") ? version.substring(1) : version;
        return range == null ? RequirementGrammar.Admission.UNKNOWN : NpmRequirement.evaluate(range, asked);
    }

    /** The node-semver range a Composer constraint means, or {@code null} for one this cannot read. */
    static String translate(String requirement) {
        if (requirement.contains("@") || requirement.contains("dev-") || requirement.contains("!")) {
            return null;                                        // a stability flag, a branch, a not-equal
        }
        StringJoiner union = new StringJoiner(" || ");
        for (String alternative : requirement.split("\\s*\\|\\|?\\s*")) {
            String intersection = intersection(alternative.trim());
            if (intersection == null) {
                return null;
            }
            union.add(intersection);
        }
        return union.toString();
    }

    private static String intersection(String alternative) {
        if (alternative.isEmpty()) {
            return null;
        }
        Matcher hyphen = HYPHEN.matcher(alternative);
        if (hyphen.matches()) {
            return hyphen.group(1) + " - " + hyphen.group(2);   // a hyphen range reads the same in both
        }
        StringJoiner range = new StringJoiner(" ");
        // An operator may stand apart from its version, so it is joined to the token after it before matching.
        String[] tokens = alternative.split("\\s*,\\s*|\\s+");
        for (int index = 0; index < tokens.length; index++) {
            String token = tokens[index];
            if (token.matches("\\^|~|>=|<=|>|<|==|=") && index + 1 < tokens.length) {
                token += tokens[++index];
            }
            String comparator = comparator(token);
            if (comparator == null) {
                return null;
            }
            range.add(comparator);
        }
        return range.toString();
    }

    private static String comparator(String token) {
        if (token.equals("*")) {
            return "*";
        }
        Matcher matcher = COMPARATOR.matcher(token);
        if (!matcher.matches()) {
            return null;
        }
        String operator = matcher.group(1);
        String bound = matcher.group(2);
        String[] parts = bound.split("\\.");
        if (parts.length > 3) {
            return null;                                        // a four-part version has no node-semver form
        }
        boolean wildcard = bound.contains("*") || bound.contains("x") || bound.contains("X");
        if (operator == null) {
            return wildcard ? bound : "=" + padded(parts);      // bare is exact however many parts it has
        }
        if (operator.equals("~") && parts.length == 2 && !wildcard) {
            int major = Integer.parseInt(parts[0]);
            return ">=" + padded(parts) + " <" + (major + 1) + ".0.0";
        }
        return (operator.equals("==") ? "=" : operator) + bound;
    }

    private static String padded(String[] parts) {
        return parts[0] + "." + (parts.length > 1 ? parts[1] : "0") + "." + (parts.length > 2 ? parts[2] : "0");
    }
}
