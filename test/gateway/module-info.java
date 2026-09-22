/**
 * The repository router driven in isolation: how a definition parses, which fallback a request resolves to,
 * what a proxy leg does with an upstream that answers badly, what the migration importers make of an
 * incumbent's asset paths, and the signature schemes a publisher's material is admitted under.
 *
 * <p>In-process throughout - a real filesystem store under a {@code @TempDir}, stub upstreams, no container and
 * no live registry. The legs that need a real Nexus or Artifactory are asserted where the containers are.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.gateway
 * @jenesis.test build.jenesis.repository.definitions
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.gateway.contract.test {
    requires build.jenesis.repository.gateway;
    requires build.jenesis.repository.gateway.testkit;
    requires build.jenesis.repository.definitions;
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.gc.store;
    requires build.jenesis.repository.staging.store;
    requires build.jenesis.repository.metadata.store;
    requires build.jenesis.repository.format.oci;
    requires build.jenesis.repository.blobs;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.maven;
    requires build.jenesis.repository.format.npm;
    requires build.jenesis.repository.format.nuget;
    requires build.jenesis.repository.format.pypi;
    requires build.jenesis.repository.format.gems;
    requires build.jenesis.repository.format.debian;
    requires build.jenesis.repository.format.composer;
    requires build.jenesis.repository.format.cocoapods;
    requires build.jenesis.repository.format.huggingface;
    requires build.jenesis.repository.format.signing;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.compliance.testkit;
    requires build.jenesis.repository.contract.testkit;
    requires build.jenesis.repository.signing.testkit;
    requires build.jenesis.repository.sigstore.testkit;
    requires build.jenesis.repository.gate.spi;
    requires build.jenesis.repository.cleanup;
    requires build.jenesis.repository.cleanup.task;
    requires build.jenesis.repository.gc;
    requires build.jenesis.repository.importer;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.metadata;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.observation;
    requires build.jenesis.repository.proxy;
    requires build.jenesis.repository.walk;
    requires org.apache.commons.compress;
    requires org.junit.jupiter;
    requires org.assertj.core;
    uses build.jenesis.repository.compliance.QualityInspector;
    uses build.jenesis.repository.importer.ImportSourceProvider;
    uses build.jenesis.repository.format.RepositoryFormat;
}
