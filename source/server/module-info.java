/**
 * The dual-layout repository server: it serves the same artifacts under the Maven layout ({@code /maven/...})
 * and the Jenesis module layout ({@code /module/...}, {@code /artifact/...}), cross-publishing a modular jar
 * uploaded the Maven way under its module name, and computing {@code maven-metadata.xml} on read only when
 * {@code jenreg.maven-metadata-compute} is on. Headless and stateless over the ArtifactStore SPI: it
 * {@code requires} no backend at all and resolves the one named by {@code JENREG_STORE} through
 * {@code ArtifactStoreProvider.resolve} over {@code ServiceLoader}, exactly as it discovers formats - the backends a
 * deployment can select are a distribution decision (the bundle requires filesystem, s3, gcs and azure), never a
 * compile-time edge from the server. An <em>explicitly selected</em> backend whose module is not on the path still
 * fails at resolution naming the selection; only the unselected default degrades to whichever bundled provider answers
 * to {@code filesystem}. It runs on a Spring Boot virtual-thread stack ({@code RepositoryApplication}, the module's
 * declared main) with Actuator observability and Spring Security gating the wire, dispatching to the
 * ServiceLoader-discovered formats over the framework-neutral core. Open so Spring can reflect over the beans and
 * controller.
 *
 * <p><b>Why {@code tomcat-embed-el} is excluded from the Jetty starter.</b> Spring Boot's
 * {@code spring-boot-starter-jetty} declares {@code org.apache.tomcat.embed:tomcat-embed-el} at compile scope -
 * it wants an Expression Language implementation and reaches for Tomcat's, even though the container is Jetty.
 * That jar is the automatic module {@code org.apache.tomcat.embed.el} and it exports {@code jakarta.el}, which
 * the real {@code jakarta.el} module also exports. On a classpath the duplicate is invisible; on a module path
 * the boot layer refuses to resolve, with {@code ResolutionException: Modules jakarta.el and
 * org.apache.tomcat.embed.el export package jakarta.el}.
 *
 * <p>Measured 2026-08-26 by removing the exclusion from all five modules that carry it and booting the console's
 * tests. <b>Removing it from one module proves nothing:</b> an exclusion is inherited by consumers, so a single
 * module's copy is masked by its siblings' and the build stays green - which is exactly the misreading that lets
 * a redundant-looking line survive unexamined. All five have to go before the failure appears.
 *
 * @jenesis.release 25
 * @jenesis.alias hdrhistogram org.hdrhistogram/HdrHistogram
 * @jenesis.exclude spring.boot.starter.jetty org.apache.tomcat.embed/tomcat-embed-el
 *
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 *
 */
open module build.jenesis.repository.server {
    requires transitive build.jenesis.repository.server.spi;
    requires build.jenesis.repository.scope;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.importer;
    requires build.jenesis.repository.posture;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.observation;
    requires tools.jackson.databind;
    requires jakarta.servlet;
    requires micrometer.observation;
    // The Prometheus registry, here rather than downstream, because scraping metrics is a Spring Boot feature and
    // not an edition's. It used to be pinned only in a downstream module, so this core had no /actuator/prometheus
    // to expose and its exposure list omitted it - which read as a policy decision and was only a decision about
    // where a dependency happened to sit. The endpoint it auto-configures is gated like every other actuator
    // surface: RepositoryAuthorizationManager binds the /actuator subtree to a deployment-wide grant.
    requires micrometer.registry.prometheus;
    // Micrometer's histogram backing, adopted under a name so it reaches the module path: it carries neither a
    // module descriptor nor an Automatic-Module-Name, which is the only reason it was on the class path.
    requires hdrhistogram;
    requires org.slf4j;
    requires ch.qos.logback.classic;
    requires ch.qos.logback.core;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    requires spring.boot;
    requires spring.boot.autoconfigure;
    requires spring.boot.webmvc;
    requires spring.boot.starter.jetty;
    requires org.eclipse.jetty.jndi;
    requires spring.boot.actuator;
    requires spring.boot.starter.actuator;
    requires spring.security.config;
    requires spring.security.core;
    requires spring.security.web;
    requires spring.boot.starter.security;
    exports build.jenesis.repository.server;
    uses build.jenesis.repository.server.RepositoryRoutingProvider;
    uses build.jenesis.repository.server.AuthorizationManagerProvider;

    provides build.jenesis.repository.server.RepositoryRoutingProvider
            with build.jenesis.repository.server.FixedTenantRoutingProvider;

    provides build.jenesis.repository.posture.SafetyAdvisor
            with build.jenesis.repository.server.NodeDivergenceAdvisor;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.server.ConsistencySettingsContributor,
                 build.jenesis.repository.server.LogsSettingsContributor,
                 build.jenesis.repository.server.RepositoryPresenceSettingsContributor;
    provides build.jenesis.repository.observation.ObservabilitySource
            with build.jenesis.repository.server.NodeConsistencyObservability,
                 build.jenesis.repository.server.RebuildScheduler.Observability;
}
