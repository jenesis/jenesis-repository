package build.jenesis.repository.dependents.requirements;

/** conda's version specs, ordered by versatile's generic scheme - see {@link CondaRequirement}. */
public final class CondaGrammar extends SchemeGrammar {

    public CondaGrammar() {
        super("conda", "generic");
    }
}
