/**
 * The console's application-service layer as its own module - the domain the admin console drives, lifted out of
 * the Spring Boot web module so any surface (the web console today, a command-line client tomorrow) binds it
 * directly rather than over the HTTP API. It holds the tenant-scoped gateways to the two products: credentials
 * ({@code CredentialService}), the artifact repository ({@code RepositoryAdmin}), the cache projects
 * ({@code CacheService}), deployment settings ({@code SettingsAdmin}) and tenant lifecycle ({@code TenantService}),
 * plus the small value types they share.
 *
 * <p>It is deliberately Spring-free: it depends only on the storage / repository SPIs and utility libraries, never
 * on Spring or the servlet API, so it carries no web coupling. The two request-scoped concerns it needs - which
 * tenant a call runs in ({@code CurrentTenant}) and who a mutation is attributed to ({@code ConsoleActor}) - are
 * named as interfaces here and implemented by whichever surface binds the layer; the web console wires these and
 * the services as beans in its own {@code DomainConfig}. The module is {@code open} only so a view technology may
 * read its record accessors reflectively.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.ui.store {
    requires build.jenesis.repository.ui;
    requires build.jenesis.repository.index.keys;
    requires org.slf4j;
    exports build.jenesis.repository.ui.store to build.jenesis.repository.ui.identity, build.jenesis.repository.server.kernel.test, build.jenesis.repository.ui.admin, build.jenesis.repository.server.kernel, build.jenesis.repository.console.api, build.jenesis.repository.auth.oidc.test, build.jenesis.repository.auth.saml.test, build.jenesis.repository.scim, build.jenesis.repository.ui.admin.test,
        build.jenesis.repository.compliance.web,
        build.jenesis.repository.search.web,
        build.jenesis.repository.dependents.web, build.jenesis.repository.forwarding.web;
    requires transitive build.jenesis.repository.cache.storage;
    requires transitive build.jenesis.repository.server;
    requires build.jenesis.repository.server.kernel;
    requires transitive build.jenesis.repository.audit;
    requires transitive build.jenesis.repository.store;
    requires transitive build.jenesis.repository.cleanup;
    requires transitive build.jenesis.repository.inventory;
    requires build.jenesis.repository.scope;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.metadata;
    requires build.jenesis.repository.observation;
    requires build.jenesis.repository.posture;
    requires transitive micrometer.observation;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.importer;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.gateway;
    requires build.jenesis.repository.definitions;
    requires build.jenesis.repository.gate.spi;
    requires build.jenesis.repository.gc;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.staging;
    requires build.jenesis.repository.search;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.upstream;
    requires build.jenesis.repository.dependents.spi;
    requires build.jenesis.repository.findings;
    requires build.jenesis.repository.health;
}
