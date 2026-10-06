package build.jenesis.repository.dependents.requirements;

/** PyPI's requirements and version order, PEP 440's as versatile implements it. */
public final class PyPiGrammar extends SchemeGrammar {

    public PyPiGrammar() {
        super("PyPI", "pypi", new PyPiRequirement());
    }
}
