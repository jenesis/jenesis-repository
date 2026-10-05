package build.jenesis.repository.dependents.requirements;

import module java.base;
import org.eclipse.aether.util.version.GenericVersionScheme;
import org.eclipse.aether.version.InvalidVersionSpecificationException;

/** Maven's requirements and version order, read by the resolver's own version scheme. */
public final class MavenGrammar extends ClosureGrammar {

    private final GenericVersionScheme scheme = new GenericVersionScheme();

    public MavenGrammar() {
        super("Maven");
    }

    @Override
    int order(String left, String right) {
        try {
            return scheme.parseVersion(left).compareTo(scheme.parseVersion(right));
        } catch (InvalidVersionSpecificationException unparsed) {
            throw new IllegalArgumentException(unparsed);
        }
    }
}
