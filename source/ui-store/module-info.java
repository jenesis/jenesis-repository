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
    exports build.jenesis.repository.ui.store;
    requires transitive build.jenesis.repository.cache.storage;
    // The build tools a project can be a cache for are the cache protocols installed.
    requires build.jenesis.repository.cache.protocol;
    requires transitive build.jenesis.repository.server;
    requires transitive build.jenesis.repository.server.kernel;
    requires transitive build.jenesis.repository.audit;
    requires transitive build.jenesis.repository.store;
    requires transitive build.jenesis.repository.cleanup;
    // The cleanup panel runs the API's own sweep and dry run, and hands their view to the screen.
    requires transitive build.jenesis.repository.cleanup.web;
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
    requires build.jenesis.repository.closure.spi;
    requires transitive build.jenesis.repository.compliance.inventory;
    requires build.jenesis.repository.gateway;
    requires build.jenesis.repository.definitions;
    requires build.jenesis.repository.gate.spi;
    requires build.jenesis.repository.gc;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.staging;
    requires build.jenesis.repository.search;
    requires build.jenesis.repository.search.service;
    // Transitive: a setting's view carries its kind, form and choices.
    requires transitive build.jenesis.repository.settings;
    requires build.jenesis.repository.upstream;
    requires build.jenesis.repository.findings;
    requires build.jenesis.repository.health;
}
