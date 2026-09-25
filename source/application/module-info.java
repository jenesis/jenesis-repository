/**
 * The repository server's BOOT MODULE (kernel/boot split): the {@code @SpringBootApplication}
 * composition root - {@code RepositoryApplication}, the focused {@code @Configuration} groups (store, gate wiring,
 * serving, workers, demo and the {@code RepositoryConfig} import shell), the security composition, the discovered
 * feature-module bridge ({@code ServerModuleImports}/{@code ServerModulesConfig}) and the boot-time probes - split
 * out of {@code build.jenesis.repository.server.kernel}, which is now the shared runtime KERNEL this module composes
 * over ({@code Repositories}, {@code Settings}, {@code LiveConfig}, {@code RepositoryProperties}, the maintenance
 * scheduler and the authorization manager). Nothing in the product depends on this module: feature-web modules
 * require the kernel and are discovered from here through {@code ServerModuleProvider}, and only the {@code bundle}
 * (and the test suites) name it - stated by the extension contract (G2). Component scan covers ONLY this
 * package: every kernel bean that must be Spring-visible is registered by an explicit {@code @Bean} method here
 * (notably {@code repositoryAuthorizationManager} in {@code RepositorySecurityConfig} - the free chain's back-off contract is
 * on that bean name). Open so Spring can reflect over the beans and controllers.
 *
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 * @jenesis.release 25
 * @jenesis.exclude spring.boot.starter.jetty org.apache.tomcat.embed/tomcat-embed-el
 *
 */
open module build.jenesis.repository.application {
    requires build.jenesis.repository.server.kernel;
    // The enforcing authorization manager the security composition registers by name (RepositorySecurityConfig).
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.gateway;
    requires build.jenesis.repository.definitions;
    requires build.jenesis.repository.gate.spi;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.audit;
    requires build.jenesis.repository.upstream;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.cleanup;
    requires build.jenesis.repository.staging;
    requires build.jenesis.repository.importer;
    requires build.jenesis.repository.ui;
    requires jakarta.servlet;
    // the rich-capabilities contribution to the ONE /api/capabilities, discovered by the
    // free RepositoryController's ServiceLoader.load(server.CapabilityContributor) - retiring the WebMvcRegistrations
    // capabilities mapping-suppression stopgap in favour of the free-core contributor SPI (a common-SPI hook, not a
    // bean override).
    provides build.jenesis.repository.server.spi.CapabilityContributor
            with build.jenesis.repository.application.DeploymentCapabilities;
    // claim the free-core import edge on module presence, so the free ImportEdgeController (conditionally
    // registered by FreeImportEdgeCondition when no provider is installed) is never created and this composition's
    // tenant-scoped ImportController is the sole import edge - retiring the WebMvcRegistrations mapping-suppression
    // stopgap in favour of the free ImportEdgeProvider SPI.
    provides build.jenesis.repository.server.spi.ImportEdgeProvider
            with build.jenesis.repository.application.RoutedImportEdge;
    requires micrometer.observation;
    requires micrometer.core;
    requires build.jenesis.repository.store.metering;
    requires micrometer.registry.prometheus;
    requires org.slf4j;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    requires spring.webmvc;
    requires spring.boot;
    requires spring.boot.autoconfigure;
    requires spring.boot.webmvc;
    requires spring.boot.starter.jetty;
    requires org.eclipse.jetty.jndi;
    // The server customizer that admits an encoded slash (EncodedSlashConfig): the connector's URI compliance and
    // the servlet handler's ambiguous-URI decoding, both Jetty's own API.
    requires spring.boot.jetty;
    requires org.eclipse.jetty.server;
    requires org.eclipse.jetty.http;
    requires org.eclipse.jetty.ee11.servlet;
    requires spring.boot.actuator;
    requires spring.boot.starter.actuator;
    requires spring.security.config;
    requires spring.security.core;
    requires spring.security.web;
    requires spring.boot.starter.security;
    exports build.jenesis.repository.application to build.jenesis.repository.bundle.full,
            build.jenesis.repository.server.kernel.test, build.jenesis.repository.degraded.test,
            // the server-module contract suite drives the real deferred import selector - the one place the
            // "configured off degrades exactly like an absent module" contract is observable - over every declared
            // ServerModuleProvider. A test-only export, like the three above it.
            // the ecosystem release-confidence harness boots this application (and not the free core's) so
            // the compliance, settings, lifecycle and maintenance wiring the feature matrix drives is present. It is
            // test support that no runtime module may require - EcosystemRunGraphTest enforces that - so the export
            // stays qualified rather than opening the composition root to the product.
            build.jenesis.repository.ecosystem.run,
            // the Keycloak rig boots this application against a real realm; it left test/server for
            // test/server-docker so a change anywhere else stops paying for a container start.
            build.jenesis.repository.server.docker.test,
            // the publish cost probe boots the whole bundle in process over a tracing store and prints what one
            // publish costs the store key by key - the composition the shipped image runs, not a test module's.
            build.jenesis.repository.load.lane.test;
}
