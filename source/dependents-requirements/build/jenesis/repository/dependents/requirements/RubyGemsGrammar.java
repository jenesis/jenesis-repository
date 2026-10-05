package build.jenesis.repository.dependents.requirements;

/** RubyGems' requirements and version order, {@code Gem::Version}'s as versatile implements it. */
public final class RubyGemsGrammar extends SchemeGrammar {

    public RubyGemsGrammar() {
        super("RubyGems", "gem");
    }
}
