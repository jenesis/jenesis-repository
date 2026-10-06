package build.jenesis.repository.dependents.requirements;

import module java.base;
import org.semver4j.Semver;

/** Cargo's requirements and version order, read by semver4j as the semantic versions crates are. */
public final class CargoGrammar extends ClosureGrammar {

    public CargoGrammar() {
        super("crates.io", new CargoRequirement());
    }

    @Override
    int order(String left, String right) {
        return Semver.parse(left).compareTo(Semver.parse(right));
    }
}
