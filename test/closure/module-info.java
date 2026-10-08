/**
 * The closure resolution in isolation over a real filesystem store with the Maven layout and its inspector installed:
 * a release's declared dependencies walked through the repository's releases and cached copies and those of the
 * repositories its fallbacks name, a cached copy's own
 * declarations read off its stored POM, a requirement taking the newest held version it admits, and every subtree
 * that cannot resolve recorded as a cut. The declared dependents the pass keeps are read through the query the module
 * provides. The pass's setting and its default are asked of the catalogue.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.closure
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.closure.test {
    requires build.jenesis.repository.closure;
    // The eviction that takes back a dependent's rows.
    requires build.jenesis.repository.cleanup;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.compliance.maven;
    requires build.jenesis.repository.compliance.scan;
    requires build.jenesis.repository.definitions;
    requires build.jenesis.repository.dependents.requirements;
    requires build.jenesis.repository.dependents.spi;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.maven;
    // A format that keeps its files in the shared Blobs namespace, whose cached copy the walk reads a manifest of.
    requires build.jenesis.repository.format.npm;
    // An RPM's coordinate names the repository it was published into, which a bill naming the package cannot know.
    requires build.jenesis.repository.format.rpm;
    requires build.jenesis.repository.findings;
    requires build.jenesis.repository.findings.store;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.metadata;
    requires build.jenesis.repository.metadata.store;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.store.testkit;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
