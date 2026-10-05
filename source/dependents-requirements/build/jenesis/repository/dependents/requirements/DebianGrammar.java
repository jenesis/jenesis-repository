package build.jenesis.repository.dependents.requirements;

/** Debian's version relations and dpkg's version order, as versatile implements it. */
public final class DebianGrammar extends SchemeGrammar {

    public DebianGrammar() {
        super("Debian", "deb");
    }
}
