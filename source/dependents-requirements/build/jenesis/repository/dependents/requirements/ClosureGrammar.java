package build.jenesis.repository.dependents.requirements;

import module java.base;
import build.jenesis.repository.closure.RequirementGrammar;

/**
 * A {@link RequirementGrammar} over this module's {@link Requirements} for one ecosystem: the admission is the
 * dependents index's own reading, so the two never answer differently about one requirement, and the order is the
 * ecosystem's version order as the same library reads it.
 */
abstract class ClosureGrammar implements RequirementGrammar {

    private final String ecosystem;

    ClosureGrammar(String ecosystem) {
        this.ecosystem = ecosystem;
    }

    @Override
    public final String ecosystem() {
        return ecosystem;
    }

    @Override
    public final Admission admits(String requirement, String version) {
        if (requirement == null || requirement.isBlank()) {
            return Admission.ADMITS;                            // nothing stated: any version a build would take
        }
        return switch (Requirements.admits(ecosystem, requirement, version)) {
            case ADMITS -> Admission.ADMITS;
            case EXCLUDES -> Admission.EXCLUDES;
            case UNKNOWN -> Admission.UNKNOWN;
        };
    }

    @Override
    public final int compare(String left, String right) {
        try {
            return order(left, right);
        } catch (RuntimeException unparsed) {
            return RequirementGrammar.FALLBACK.compare(left, right);
        }
    }

    /** The ecosystem's order of two versions, raising for a version its library does not parse. */
    abstract int order(String left, String right);
}
