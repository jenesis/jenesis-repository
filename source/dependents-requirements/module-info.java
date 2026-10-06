/**
 * Whether a declared requirement admits a version, for the ecosystems whose requirement grammar a published library
 * evaluates: Maven's through the resolver's own version scheme, npm's through semver4j, and Cargo's and Composer's
 * through semver4j after translating each into the node-semver range it means. What the dependents index's
 * declared tier shows beside each row when a caller names a version - a marker, never an input to a blast radius - and
 * the grammar a closure takes the newest held version a requirement admits by, each ecosystem's versions ordered by
 * the same library ({@link build.jenesis.repository.closure.spi.RequirementGrammar}). An ecosystem this module does not
 * evaluate answers unknown rather than a guess.
 *
 * <p>The ecosystems whose versions are not semantic versions - PyPI, NuGet, RubyGems, Go, Debian, RPM, Alpine and
 * conda - read their own requirement spelling into comparators here and order versions by versatile, which implements
 * each ecosystem's own version order (PEP 440, NuGet's, {@code Gem::Version}'s, Go's, dpkg's, {@code rpmvercmp}'s,
 * apk's); Helm's constraints mean what node-semver's do and are evaluated as npm's are.
 *
 * <p>semver4j declares its module name in its manifest only and the hosted module index does not carry it, so the
 * alias names the artifact and the pin file's coordinate entry carries its version; versatile is aliased the same way,
 * and so is the descriptor-less {@code maven-artifact}, which reaches the module path only through the alias giving it
 * the name versatile's descriptor requires.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 * @jenesis.alias org.semver4j org.semver4j/semver4j
 * @jenesis.alias io.github.nscuro.versatile.core io.github.nscuro/versatile-core
 * @jenesis.alias org.apache.maven.v3.artifact org.apache.maven/maven-artifact
 */
module build.jenesis.repository.dependents.requirements {
    requires build.jenesis.repository.closure.spi;
    requires org.apache.maven.resolver.util;
    requires org.semver4j;
    requires io.github.nscuro.versatile.core;
    exports build.jenesis.repository.dependents.requirements;
    provides build.jenesis.repository.closure.spi.RequirementGrammar
            with build.jenesis.repository.dependents.requirements.MavenGrammar,
                    build.jenesis.repository.dependents.requirements.NpmGrammar,
                    build.jenesis.repository.dependents.requirements.CargoGrammar,
                    build.jenesis.repository.dependents.requirements.ComposerGrammar,
                    build.jenesis.repository.dependents.requirements.PyPiGrammar,
                    build.jenesis.repository.dependents.requirements.NuGetGrammar,
                    build.jenesis.repository.dependents.requirements.RubyGemsGrammar,
                    build.jenesis.repository.dependents.requirements.GoGrammar,
                    build.jenesis.repository.dependents.requirements.DebianGrammar,
                    build.jenesis.repository.dependents.requirements.RpmGrammar,
                    build.jenesis.repository.dependents.requirements.AlpineGrammar,
                    build.jenesis.repository.dependents.requirements.CondaGrammar,
                    build.jenesis.repository.dependents.requirements.HelmGrammar;
}
