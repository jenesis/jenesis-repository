/**
 * End-to-end test of the dual-layout repository. It boots the real {@link build.jenesis.repository.server.RepositoryApplication}
 * on an ephemeral port over a temporary filesystem store, publishes artifacts over HTTP, and resolves them back
 * under both layouts - a Maven library that carries a module name is consumable by module name, and a module is
 * consumable by its Maven coordinate, off one content-addressed blob. The {@code jdk.httpserver} requirement backs
 * the in-test fake Nexus and Artifactory upstreams the import tests drive, not the repository server itself.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.server
 * @jenesis.alias wiremock.core org.wiremock/wiremock-core
 * @jenesis.alias wiremock.jetty org.wiremock/wiremock-jetty
 * @jenesis.alias wiremock.httpclient org.wiremock/wiremock-httpclient-apache5
 * @jenesis.attach org.mockito
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.test {
    requires build.jenesis.repository.scope;
    requires build.jenesis.repository.contract.testkit;
    // The shared traversal probe vectors: ImporterContractTest probes every discovered importer with the same list the
    // format kit probes every format with, so the importer seam cannot rot into its own private set of shapes.
    requires build.jenesis.repository.format.testkit;
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.server.spi;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    // The shared fault fixture: MavenCrossPublishSequenceTest drives a real store failure at each step of the Maven
    // cross-publish rather than substituting a throwing ModuleView, so the crash windows it asserts are the ones a
    // backend outage really produces.
    requires build.jenesis.repository.store.testkit;
    // The capability-signal census reads GarbageCollectorProvider.installed() and WalkProvider.installed() off the
    // linked types, not just off their sources: a rename a text scan stops matching reads exactly like a pass.
    requires build.jenesis.repository.gc;
    requires build.jenesis.repository.walk;
    // ... and its shipped implementation, so the same suite can run a real rebuild pass over the residue a crashed
    // cross-publish leaves and prove the repair converges.
    requires build.jenesis.repository.walk.store;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.proxy;
    requires build.jenesis.repository.oidc;
    requires build.jenesis.repository.usage;
    requires build.jenesis.repository.ratelimit;
    requires build.jenesis.repository.format.maven;
    requires build.jenesis.repository.format.jenesis;
    requires build.jenesis.repository.format.oci;
    requires build.jenesis.repository.format.raw;
    requires build.jenesis.repository.importer;
    requires build.jenesis.repository.importer.nexus;
    requires build.jenesis.repository.importer.artifactory;
    requires build.jenesis.repository.importer.jenesis;
    requires build.jenesis.repository.importer.maven;
    requires build.jenesis.repository.importer.index;
    requires build.jenesis.repository.posture;
    requires build.jenesis.repository.observation;
    requires micrometer.observation;
    requires tools.jackson.databind;
    requires jakarta.servlet;
    requires java.net.http;
    requires org.slf4j;
    requires ch.qos.logback.classic;
    requires ch.qos.logback.core;
    requires org.junit.jupiter;
    requires org.assertj.core;
    // RepositoryAuthorizationManagerFailClosedTest drives the AuthorizationManager directly (no booted server), so the
    // test module reads the Spring Security types it takes and returns - RequestAuthorizationContext (web) and
    // AuthorizationResult (core) - which the server module requires but does not re-export.
    requires spring.security.web;
    requires spring.security.core;
    requires spring.beans;
    requires spring.core;
    // ContendedWriteTest reads the controller's @ExceptionHandler to hold that a contended write's answer is bound.
    requires spring.web;

    requires wiremock.core;
    requires wiremock.jetty;
    requires wiremock.httpclient;
    requires org.mockito;
    // ImporterContractTest discovers every RepositoryImporter the way the server does - ServiceLoader over the
    // RepositoryFormat providers, filtered to the import capability - so it declares the same service dependency.
    uses build.jenesis.repository.format.RepositoryFormat;
    // WSPI.2 (b): the two publication hooks are one discovered seam - a PublishInterceptor IS a PublicationObserver,
    // so the screen fixtures register through the single PublicationObserver clause and Publication splits them into
    // the verdict chain by instanceof.
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.test.RecordingObserver,
                    build.jenesis.repository.test.MarkerInterceptor,
                    build.jenesis.repository.test.CountingInterceptor,
                    build.jenesis.repository.test.CensusObserver;
    // Register a test ImportEdgeProvider so the running free server discovers it via ServiceLoader exactly as a
    // richer distribution would, proving the free import edge yields (its mapping is not registered) when a distribution
    // owns the edge - no WebMvcRegistrations suppression. Inert by default (a required-config gate), activated only by
    // the yield test, so every other import test still sees the free edge served.
    provides build.jenesis.repository.server.spi.ImportEdgeProvider
            with build.jenesis.repository.test.TestImportEdgeProvider;
}
