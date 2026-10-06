package build.jenesis.repository.dependents.requirements;

import module java.base;
import org.semver4j.Semver;

/** npm's requirements and version order, read by semver4j. */
public final class NpmGrammar extends ClosureGrammar {

    public NpmGrammar() {
        super("npm", new NpmRequirement());
    }

    @Override
    int order(String left, String right) {
        return Semver.parse(left).compareTo(Semver.parse(right));
    }
}
