package build.jenesis.repository.dependents.requirements;

import module java.base;
import build.jenesis.repository.closure.spi.RequirementGrammar;
import org.eclipse.aether.util.version.GenericVersionScheme;
import org.eclipse.aether.version.InvalidVersionSpecificationException;
import org.eclipse.aether.version.VersionConstraint;

/**
 * Maven's requirement grammar, read by the resolver's own version scheme: a range ({@code [1.0,2.0)}, a union of
 * them) admits exactly the versions inside it, as Maven's resolution would.
 *
 * <p>Ivy's revisions ride the same grammar, since an ivy.xml declares coordinates in the Maven ecosystem: an exclusive
 * bound written with its bracket turned outward ({@code ]1.0,2.0[}), a prefix ({@code 1.0+}, every version beginning
 * so) and {@code latest.<status>}, which admits every version and lets the newest be taken.
 *
 * <p>A bare version is a soft requirement - a recommendation that dependency mediation may override with another
 * version the graph asks for - so it admits the version it names and says nothing about any other: those answer
 * {@link RequirementGrammar.Admission#UNKNOWN}, never a refusal the resolver would not make.
 */
final class MavenRequirement implements ClosureGrammar.Reader {

    private final GenericVersionScheme scheme = new GenericVersionScheme();

    @Override
    public RequirementGrammar.Admission admits(String requirement, String version) {
        // Ivy's dynamic revisions, which an ivy.xml in the Maven ecosystem carries and Maven itself never writes.
        if (requirement.startsWith("latest.")) {
            return RequirementGrammar.Admission.ADMITS;
        }
        if (requirement.endsWith("+")) {
            return version.startsWith(requirement.substring(0, requirement.length() - 1))
                    ? RequirementGrammar.Admission.ADMITS : RequirementGrammar.Admission.EXCLUDES;
        }
        requirement = ivyBounds(requirement);
        try {
            VersionConstraint constraint = scheme.parseVersionConstraint(requirement);
            boolean contains = constraint.containsVersion(scheme.parseVersion(version));
            if (constraint.getRange() == null && !contains) {
                return RequirementGrammar.Admission.UNKNOWN;           // a soft requirement naming another version
            }
            return contains ? RequirementGrammar.Admission.ADMITS : RequirementGrammar.Admission.EXCLUDES;
        } catch (InvalidVersionSpecificationException | RuntimeException unreadable) {
            return RequirementGrammar.Admission.UNKNOWN;
        }
    }

    /** An Ivy range in Maven's spelling: Ivy writes an exclusive bound with the bracket turned outward
     *  ({@code ]1.0,2.0[}), where Maven writes a parenthesis. */
    private static String ivyBounds(String requirement) {
        if (!requirement.contains(",")) {
            return requirement;
        }
        String range = requirement.startsWith("]") ? "(" + requirement.substring(1) : requirement;
        return range.endsWith("[") ? range.substring(0, range.length() - 1) + ")" : range;
    }
}
