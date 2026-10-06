package build.jenesis.repository.dependents.requirements;

/** NuGet's version ranges and version order, as versatile implements NuGet's. */
public final class NuGetGrammar extends SchemeGrammar {

    public NuGetGrammar() {
        super("NuGet", "nuget", new NuGetRequirement());
    }
}
