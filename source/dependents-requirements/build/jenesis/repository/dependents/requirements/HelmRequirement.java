package build.jenesis.repository.dependents.requirements;

import module java.base;
import build.jenesis.repository.closure.RequirementGrammar;

/**
 * Helm's dependency constraint, as a {@code Chart.yaml} writes it: the Masterminds semantic-version constraints, which
 * mean what node-semver's ranges mean in a different spelling - a comma or a space between comparators both mean
 * "and", {@code ||} "or", and a space may sit between an operator and its version - so the constraint is translated
 * into the node-semver range it means and evaluated as npm's is.
 */
final class HelmRequirement implements ClosureGrammar.Reader {

    private static final Pattern SPACED_OPERATOR = Pattern.compile("(>=|<=|!=|=|>|<|~|\\^)\\s+");

    @Override
    public RequirementGrammar.Admission admits(String requirement, String version) {
        String range = SPACED_OPERATOR.matcher(requirement.replace(",", " ")).replaceAll("$1").strip();
        return range.contains("!=") || !NpmRequirement.range(range) ? RequirementGrammar.Admission.UNKNOWN
                : NpmRequirement.evaluate(range, version);
    }
}
