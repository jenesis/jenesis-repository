/**
 * The JVM publishing quality gate as a plugin module: it provides
 * {@link build.jenesis.repository.compliance.QualityInspector} for the two JVM layouts - Maven's
 * {@code /maven/...} and the Jenesis module layout's {@code /module/...} - reading a coordinate and its declared
 * licenses out of a POM or jar so the shared compliance gate can assess them. Licences are taken from the first
 * declaration source that declares anything: the POM, then the CycloneDX SBOM a Jenesis build publishes beside the
 * artifact or embeds in the jar, then the OSGi {@code Bundle-License} header. For a POM it also yields a subject per
 * dependency in the closure - read hermetically out of the published SBOM when one is stored, and only otherwise
 * resolved over the network through the Jenesis Maven resolver. Discovered through {@code provides}; npm, PyPI,
 * NuGet and Go each add their own inspector module the same way.
 *
 * <p>{@code build.jenesis.repository.dependency} is the shared SBOM primitive - the CycloneDX/SPDX parsers and the
 * {@code Sbom-Location}-driven embedded-document extractor the reverse-dependency index and the reachability engine
 * already read artifacts through. Required rather than re-implemented: a second SBOM locator or parser in a
 * compliance module is precisely the copied mechanism the thin-core rule forbids.
 *
 * @jenesis.release 25
 * @jenesis.alias build.jenesis build.jenesis/build.jenesis
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.compliance.maven {
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.format.java;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.dependency;
    requires build.jenesis;
    requires java.xml;
    requires org.slf4j;
    // Gradle Module Metadata is a JSON descriptor; a maintained parser rather than a hand-rolled one (§8).
    requires tools.jackson.databind;
    exports build.jenesis.repository.compliance.maven to build.jenesis.repository.gateway.test;
    provides build.jenesis.repository.compliance.QualityInspector
            with build.jenesis.repository.compliance.maven.MavenQualityInspector;
}
