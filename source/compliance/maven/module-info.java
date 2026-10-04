/**
 * The JVM publishing quality gate as a plugin module: a {@link build.jenesis.repository.compliance.QualityInspector}
 * for Maven's {@code /maven/...} and the module layout's {@code /module/...}, reading a coordinate and its declared
 * licences - from the POM, then a CycloneDX SBOM beside or inside the artifact, then {@code Bundle-License} - and for a
 * POM a subject per dependency of its closure, read from a published SBOM or resolved by Maven Resolver - against the
 * POMs this repository holds, then the one repository an operator named - within a bound. {@code
 * build.jenesis.repository.dependency} supplies the shared SBOM parsers and extractor. The resolver fetches over the
 * screened HTTP client, so its Apache transport, and the HTTP client and logging bridge that come with it, are left out.
 *
 * @jenesis.release 25
 * @jenesis.alias org.apache.maven.resolver.supplier org.apache.maven.resolver/maven-resolver-supplier-mvn3
 * @jenesis.exclude org.apache.maven.resolver.supplier org.apache.maven.resolver/maven-resolver-transport-apache
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.compliance.maven {
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.format.java;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.dependency;
    requires org.apache.maven.resolver;
    requires org.apache.maven.resolver.spi;
    requires org.apache.maven.resolver.util;
    requires org.apache.maven.resolver.supplier;
    requires build.jenesis.repository.net.http;
    requires build.jenesis.repository.xml;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.observation;
    requires org.slf4j;
    // Gradle Module Metadata is JSON.
    requires tools.jackson.databind;
    exports build.jenesis.repository.compliance.maven;
    provides build.jenesis.repository.compliance.QualityInspector
            with build.jenesis.repository.compliance.maven.MavenQualityInspector;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.compliance.maven.ClosureSettingsContributor;
    provides build.jenesis.repository.observation.ObservabilitySource
            with build.jenesis.repository.compliance.maven.ClosureObservability;
}
