/**
 * The browse, search and licence-inventory read surface as a removable server module: a
 * {@link build.jenesis.repository.server.kernel.ServerModuleProvider} the server imports by {@code ServiceLoader}. A
 * thin Spring adapter over {@code Repositories}, the store-backed {@link build.jenesis.repository.inventory} listing,
 * the one {@link build.jenesis.repository.search.service.RepositorySearch} and the licence inventory's stored report
 * ({@link build.jenesis.repository.compliance.inventory.LicenseReport}), plus the inventory's console screen. Open so
 * Spring can reflect over the controllers.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.search.web {
    exports build.jenesis.repository.search.web;
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
