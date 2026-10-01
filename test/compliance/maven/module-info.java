/**
 * What the Maven and OCI inspectors read for a full-text index out of the documents they already parse for a licence:
 * a POM's own description, and an image manifest's description and authors annotations - and where the Maven
 * inspector resolves a POM's dependency closure from, and how far. In process, over a repository the tests write to a
 * directory, with no network or container.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.compliance.maven
 * @jenesis.test build.jenesis.repository.compliance.oci
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.compliance.maven.test {
    requires build.jenesis.repository.compliance.maven;
    requires build.jenesis.repository.compliance.oci;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.observation;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
