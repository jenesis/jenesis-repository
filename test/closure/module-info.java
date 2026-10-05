/**
 * The closure resolution in isolation over a real filesystem store with the Maven layout and its inspector installed:
 * a release's declared dependencies walked through the repository's releases and cached copies and those of the
 * repositories its fallbacks name, a cached copy's own
 * declarations read off its stored POM, a requirement taking the newest held version it admits, and every subtree
 * that cannot resolve recorded as a cut. The pass's setting and its default are asked of the catalogue.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.closure
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.closure.test {
    requires build.jenesis.repository.closure;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.compliance.maven;
    requires build.jenesis.repository.definitions;
    requires build.jenesis.repository.dependents.requirements;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.maven;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.metadata;
    requires build.jenesis.repository.metadata.store;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
