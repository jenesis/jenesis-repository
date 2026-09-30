/**
 * The one XML reader the product parses untrusted documents with ({@code Xml}): a POM, a {@code maven-metadata.xml},
 * a {@code .nuspec}, an Ivy descriptor, a CycloneDX BOM - each riding inside an uploaded or proxied artifact.
 *
 * <p>A module of its own because the readers live in formats, inspectors, an importer and the dependency parser,
 * none of which may require another, and each had grown its own factory set-up with a different subset of the
 * hardening. None of them set an error handler either, so the JDK's default one wrote {@code [Fatal Error] ...} to
 * the server's stderr for every body that was not XML, although every caller already handled the failure.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.xml {
    requires transitive java.xml;
    exports build.jenesis.repository.xml;
}
