/**
 * The console's application-service layer: the tenant-scoped services the admin console drives in process -
 * credentials, repositories, cache projects, settings, tenants. Spring-free; the request-scoped concerns
 * ({@code CurrentTenant}, {@code ConsoleActor}) are interfaces the binding surface implements. Open so a view technology
 * may read its record accessors.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.ui.store {
    requires build.jenesis.repository.ui;
    requires build.jenesis.repository.index.keys;
    requires org.slf4j;
    exports build.jenesis.repository.ui.store to build.jenesis.repository.ui.store.test,
            build.jenesis.repository.format.lifecycle.console,
            build.jenesis.repository.ui.identity,
            build.jenesis.repository.auth.keylogin,
            build.jenesis.repository.server.kernel.test,
            build.jenesis.repository.ui.admin,
            build.jenesis.repository.server.kernel,
            build.jenesis.repository.console.api,
            build.jenesis.repository.auth.oidc.test,
            build.jenesis.repository.auth.saml.test,
            build.jenesis.repository.scim,
            build.jenesis.repository.ui.admin.test,
            build.jenesis.repository.ui.admin.installed.test,
            build.jenesis.repository.compliance.web,
            build.jenesis.repository.search.web,
            build.jenesis.repository.dependents.web,
            build.jenesis.repository.forwarding.web;
    requires transitive build.jenesis.repository.cache.storage;
    requires transitive build.jenesis.repository.server;
    requires transitive build.jenesis.repository.server.kernel;
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
    requires transitive build.jenesis.repository.compliance.inventory;
    requires build.jenesis.repository.gateway;
    requires build.jenesis.repository.definitions;
    requires build.jenesis.repository.gate.spi;
    requires build.jenesis.repository.gc;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.staging;
    requires build.jenesis.repository.search;
    requires build.jenesis.repository.search.service;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.upstream;
    requires build.jenesis.repository.dependents.spi;
    requires build.jenesis.repository.findings;
    requires build.jenesis.repository.health;
}
