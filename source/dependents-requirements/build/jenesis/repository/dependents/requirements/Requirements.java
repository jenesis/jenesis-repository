package build.jenesis.repository.dependents.requirements;

import module java.base;

/**
 * Whether a manifest's requirement admits a version, asked of the grammar of the ecosystem that wrote it.
 *
 * <p>Three answers, and the third is the one that keeps the other two honest. {@link Verdict#UNKNOWN} is what an
 * ecosystem with no grammar here gets, and what a grammar gives for a requirement it cannot read as a range - an npm
 * dist-tag, a git or file source, a Maven soft requirement naming another version - or for a version its scheme
 * does not parse. Each grammar is a published library's reading of the specification rather than one written here,
 * so a verdict is the one the ecosystem's own tooling would reach.
 */
public final class Requirements {

    /** What a requirement says about one version. */
    public enum Verdict {
        /** The requirement admits the version: a client resolving it may install that version. */
        ADMITS,
        /** The requirement excludes the version: no client resolving it installs that version. */
        EXCLUDES,
        /** Nothing here can say - no grammar for the ecosystem, or a requirement or version it cannot read. */
        UNKNOWN
    }

    /** A grammar, keyed by the ecosystem name a format declares. */
    interface Grammar {

        Verdict admits(String requirement, String version);
    }

    private static final Map<String, Grammar> GRAMMARS = Map.ofEntries(
            Map.entry("Maven", new MavenRequirement()),
            Map.entry("npm", new NpmRequirement()),
            Map.entry("crates.io", new CargoRequirement()),
            Map.entry("Packagist", new ComposerRequirement()),
            Map.entry("PyPI", new PyPiRequirement()),
            Map.entry("NuGet", new NuGetRequirement()),
            Map.entry("RubyGems", new RubyGemsRequirement()),
            Map.entry("Go", new GoRequirement()),
            Map.entry("Debian", new DebianRequirement()),
            Map.entry("RPM", new RpmRequirement()),
            Map.entry("Alpine", new AlpineRequirement()),
            Map.entry("conda", new CondaRequirement()),
            Map.entry("Helm", new HelmRequirement()));

    private Requirements() {
    }

    /** Whether {@code requirement}, as written in an {@code ecosystem} manifest, admits {@code version}. */
    public static Verdict admits(String ecosystem, String requirement, String version) {
        Grammar grammar = ecosystem == null ? null : GRAMMARS.get(ecosystem);
        if (grammar == null || requirement == null || requirement.isBlank() || version == null || version.isBlank()) {
            return Verdict.UNKNOWN;                             // no grammar, or nothing stated to evaluate
        }
        return grammar.admits(requirement.trim(), version.trim());
    }

    /** The ecosystems whose requirements this evaluates. */
    public static Set<String> ecosystems() {
        return new TreeSet<>(GRAMMARS.keySet());
    }
}
