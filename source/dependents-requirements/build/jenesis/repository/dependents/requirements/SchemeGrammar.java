package build.jenesis.repository.dependents.requirements;

import module java.base;

/** A {@link ClosureGrammar} whose versions are ordered by one versatile scheme, as its {@link SchemeRequirement}
 *  compares them. */
abstract class SchemeGrammar extends ClosureGrammar {

    private final String scheme;

    SchemeGrammar(String ecosystem, String scheme, Reader reader) {
        super(ecosystem, reader);
        this.scheme = scheme;
    }

    @Override
    final int order(String left, String right) {
        return SchemeRequirement.order(scheme, left, right);
    }
}
