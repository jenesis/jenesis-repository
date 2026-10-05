/**
 * Whether a declared requirement admits a version, for the ecosystems whose requirement grammar a published library
 * evaluates: Maven's through the resolver's own version scheme, npm's through semver4j, and Cargo's and Composer's
 * through semver4j after translating each into the node-semver range it means. What the dependents index's
 * declared tier shows beside each row when a caller names a version - a marker, never an input to a blast radius - and
 * the grammar a closure takes the newest held version a requirement admits by, each ecosystem's versions ordered by
 * the same library ({@link build.jenesis.repository.closure.RequirementGrammar}). An ecosystem this module does not
 * evaluate answers unknown rather than a guess.
 *
 * <p>semver4j declares its module name in its manifest only and the hosted module index does not carry it, so the
 * alias names the artifact and the pin file's coordinate entry carries its version.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 * @jenesis.alias org.semver4j org.semver4j/semver4j
 */
module build.jenesis.repository.dependents.requirements {
    requires build.jenesis.repository.closure;
    requires org.apache.maven.resolver.util;
    requires org.semver4j;
    exports build.jenesis.repository.dependents.requirements;
    provides build.jenesis.repository.closure.RequirementGrammar
            with build.jenesis.repository.dependents.requirements.MavenGrammar,
                    build.jenesis.repository.dependents.requirements.NpmGrammar,
                    build.jenesis.repository.dependents.requirements.CargoGrammar,
                    build.jenesis.repository.dependents.requirements.ComposerGrammar;
}
