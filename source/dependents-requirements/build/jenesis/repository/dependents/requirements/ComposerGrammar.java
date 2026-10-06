package build.jenesis.repository.dependents.requirements;

import module java.base;
import org.semver4j.Semver;

/** Composer's requirements and version order: a version read by semver4j once its {@code v} prefix and any parts past
 *  three are coerced away, as its constraint is translated. */
public final class ComposerGrammar extends ClosureGrammar {

    public ComposerGrammar() {
        super("Packagist", new ComposerRequirement());
    }

    @Override
    int order(String left, String right) {
        return Semver.coerce(strip(left)).compareTo(Semver.coerce(strip(right)));
    }

    private static String strip(String version) {
        return version.startsWith("v") ? version.substring(1) : version;
    }
}
