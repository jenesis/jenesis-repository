package build.jenesis.repository.dependents.requirements;

/** RPM's version relations and {@code rpmvercmp}'s order, as versatile implements it. */
public final class RpmGrammar extends SchemeGrammar {

    public RpmGrammar() {
        super("RPM", "rpm", new RpmRequirement());
    }
}
