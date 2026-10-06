package build.jenesis.repository.dependents.requirements;

/** Alpine's version constraints and apk's version order, as versatile implements it. */
public final class AlpineGrammar extends SchemeGrammar {

    public AlpineGrammar() {
        super("Alpine", "alpine", new AlpineRequirement());
    }
}
