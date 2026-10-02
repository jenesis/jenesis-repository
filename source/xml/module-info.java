/**
 * The one XML reader the product parses untrusted documents with ({@code Xml}): a POM, a {@code maven-metadata.xml},
 * a {@code .nuspec}, an Ivy descriptor, a CycloneDX BOM - each riding inside an uploaded or proxied artifact.
 *
 * <p>A module of its own because the readers live in formats, inspectors, an importer and the dependency parser, none
 * of which may require another, and every one of them needs the same hardening and the same silent error handler.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.xml {
    requires transitive java.xml;
    exports build.jenesis.repository.xml;
}
