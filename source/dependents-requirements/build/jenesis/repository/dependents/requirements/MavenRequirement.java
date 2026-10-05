package build.jenesis.repository.dependents.requirements;

import module java.base;
import org.eclipse.aether.util.version.GenericVersionScheme;
import org.eclipse.aether.version.InvalidVersionSpecificationException;
import org.eclipse.aether.version.VersionConstraint;

/**
 * Maven's requirement grammar, read by the resolver's own version scheme: a range ({@code [1.0,2.0)}, a union of
 * them) admits exactly the versions inside it, as Maven's resolution would.
 *
 * <p>A bare version is a soft requirement - a recommendation that dependency mediation may override with another
 * version the graph asks for - so it admits the version it names and says nothing about any other: those answer
 * {@link Requirements.Verdict#UNKNOWN}, never a refusal the resolver would not make.
 */
final class MavenRequirement implements Requirements.Grammar {

    private final GenericVersionScheme scheme = new GenericVersionScheme();

    @Override
    public Requirements.Verdict admits(String requirement, String version) {
        try {
            VersionConstraint constraint = scheme.parseVersionConstraint(requirement);
            boolean contains = constraint.containsVersion(scheme.parseVersion(version));
            if (constraint.getRange() == null && !contains) {
                return Requirements.Verdict.UNKNOWN;           // a soft requirement naming another version
            }
            return contains ? Requirements.Verdict.ADMITS : Requirements.Verdict.EXCLUDES;
        } catch (InvalidVersionSpecificationException | RuntimeException unreadable) {
            return Requirements.Verdict.UNKNOWN;
        }
    }
}
