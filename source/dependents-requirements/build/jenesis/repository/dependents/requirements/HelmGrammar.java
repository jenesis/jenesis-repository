package build.jenesis.repository.dependents.requirements;

import module java.base;
import org.semver4j.Semver;

/** Helm's dependency constraints and chart version order, read by semver4j as the semantic versions charts are. */
public final class HelmGrammar extends ClosureGrammar {

    public HelmGrammar() {
        super("Helm");
    }

    @Override
    int order(String left, String right) {
        return Semver.parse(left).compareTo(Semver.parse(right));
    }
}
