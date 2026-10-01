/**
 * The browse / search / licence-inventory read surface as a removable server feature module: it provides
 * {@link build.jenesis.repository.server.kernel.ServerModuleProvider}, so the repository server imports its
 * configuration through {@code ServiceLoader} discovery and names no browse or search endpoint. A thin Spring
 * {@code web} adapter over the {@code Repositories} resolver, the store-backed {@link build.jenesis.repository.inventory}
 * listing and the one {@link build.jenesis.repository.search.service.RepositorySearch} - a lookup by name, or the
 * full-text index where a repository has it switched on - and the licence inventory's stored report, counted on
 * request by {@link build.jenesis.repository.compliance.inventory.LicenseReport} whether or not the index is on. With
 * this module absent the server carries no {@code /api/browse}, {@code /api/search} or {@code /api/licenses} route.
 * Open so Spring can reflect over the controller and its configuration.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.search.web {
    exports build.jenesis.repository.search.web to build.jenesis.repository.web.test,
            build.jenesis.repository.server.kernel.test;
    requires build.jenesis.repository.server.kernel;
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.search;
    requires build.jenesis.repository.search.service;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.compliance.inventory;
    requires build.jenesis.repository.store;
    requires jakarta.servlet;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    provides build.jenesis.repository.server.kernel.ServerModuleProvider
            with build.jenesis.repository.search.web.SearchWebModule;
    // The console seam this module contributes its screen through, and the read service it renders.
    requires build.jenesis.repository.ui;
    requires build.jenesis.repository.ui.store;
    requires thymeleaf.spring6;
    provides build.jenesis.repository.ui.ConsoleModuleProvider
            with build.jenesis.repository.search.web.SearchConsoleModule;
}
