package build.jenesis.repository.dependents.requirements;

import module java.base;
import build.jenesis.repository.closure.spi.RequirementGrammar;

/**
 * A {@link RequirementGrammar} for one ecosystem over its {@link Reader}: what a requirement admits is the reader's -
 * a published library's reading of the ecosystem's specification rather than one written here, so an answer is the
 * one the ecosystem's own tooling would reach - and the order is the ecosystem's version order as the same library
 * reads it. A blank requirement states nothing and admits every version a build would take; a blank version is
 * {@link Admission#UNKNOWN}.
 */
abstract class ClosureGrammar implements RequirementGrammar {

    /** An ecosystem's reading of a requirement it states, asked only of a requirement and a version that both say
     *  something: {@link Admission#UNKNOWN} for a requirement it cannot read as a range - an npm dist-tag, a git or
     *  file source, a Maven soft requirement naming another version - or a version its scheme does not parse. */
    interface Reader {

        Admission admits(String requirement, String version);
    }

    private final String ecosystem;

    private final Reader reader;

    ClosureGrammar(String ecosystem, Reader reader) {
        this.ecosystem = ecosystem;
        this.reader = reader;
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
        if (version == null || version.isBlank()) {
            return Admission.UNKNOWN;
        }
        return reader.admits(requirement.strip(), version.strip());
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
