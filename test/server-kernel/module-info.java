/**
 * The server kernel driven in isolation: the settings catalogue and its refresh, the maintenance scheduler's
 * lease and containment, the module-capability and SPI catalogues an operator reads, the metering store
 * decorator, the download tracker, first-run hardening, and the storage namespaces a plug-in may claim.
 *
 * <p>In-process throughout - a real filesystem store under a {@code @TempDir}, no server started, no container.
 * What a booted deployment does with these parts is asserted where the deployment is composed.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.server.kernel
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.server.kernel.contract.test {
    requires build.jenesis.repository.server.kernel;
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.server.spi;
    requires build.jenesis.repository.scope;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.gc.store;
    requires build.jenesis.repository.staging.store;
    requires build.jenesis.repository.metadata.store;
    requires build.jenesis.repository.cleanup.task;
    requires build.jenesis.repository.format.oci;
    requires build.jenesis.repository.store.metering;
    requires build.jenesis.repository.store.testkit;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.observation;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.metadata;
    requires build.jenesis.repository.blobs;
    requires build.jenesis.repository.bounds;
    requires build.jenesis.repository.cleanup;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.downloads;
    requires build.jenesis.repository.staging;
    requires build.jenesis.repository.audit;
    requires build.jenesis.repository.findings;
    requires build.jenesis.repository.health;
    requires build.jenesis.repository.dependents.spi;
    requires build.jenesis.repository.upstream.store;
    requires build.jenesis.repository.gc;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.maven;
    requires build.jenesis.repository.format.oci.inventory;
    requires build.jenesis.repository.walk;
    requires jakarta.servlet;
    requires micrometer.core;
    requires org.slf4j;
    requires ch.qos.logback.classic;
    requires ch.qos.logback.core;
    requires spring.core;
    requires org.mockito;
    requires org.junit.jupiter;
    requires org.assertj.core;
    uses build.jenesis.repository.server.kernel.ServerModuleProvider;
    uses build.jenesis.repository.format.RepositoryFormat;
    uses build.jenesis.repository.observation.ObservabilitySource;
    uses build.jenesis.repository.walk.WalkConsumer;
}
