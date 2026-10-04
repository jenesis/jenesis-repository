/**
 * The JVM publishing quality gate as a plugin module: a {@link build.jenesis.repository.compliance.QualityInspector}
 * for Maven's {@code /maven/...} and the module layout's {@code /module/...}, reading a coordinate and its declared
 * licences - from the POM, then a CycloneDX SBOM beside or inside the artifact, then {@code Bundle-License} - and for a
 * POM a subject per dependency of the closure a CycloneDX document published beside it declares. {@code
 * build.jenesis.repository.dependency} supplies the shared SBOM parsers and extractor.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.compliance.maven {
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.format.java;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.dependency;
    requires build.jenesis.repository.xml;
    requires org.slf4j;
    // Gradle Module Metadata is JSON.
    requires tools.jackson.databind;
    exports build.jenesis.repository.compliance.maven;
    provides build.jenesis.repository.compliance.QualityInspector
            with build.jenesis.repository.compliance.maven.MavenQualityInspector;
}
