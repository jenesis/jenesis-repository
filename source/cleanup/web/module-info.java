/**
 * The repository-maintenance HTTP surface as a removable server module: a
 * {@link build.jenesis.repository.server.kernel.ServerModuleProvider} the server imports by {@code ServiceLoader}. A
 * thin Spring adapter over the retention engine ({@link build.jenesis.repository.cleanup.RetentionSweeper}) and the
 * repository inventory ({@link build.jenesis.repository.inventory.StoreRepositoryInventory}), resolved per tenant and
 * repository: sweeping and planning cleanups, reading and setting retention, and pinning versions against retention.
 * Without a retention module the cleanup and retention endpoints answer {@code 501}. Open so Spring can reflect over
 * the controller.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.cleanup.web {
    exports build.jenesis.repository.cleanup.web;
    requires build.jenesis.repository.server.kernel;
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.audit;
    requires build.jenesis.repository.cleanup;
    requires build.jenesis.repository.gc;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.store;
    requires micrometer.observation;
    requires jakarta.servlet;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    provides build.jenesis.repository.server.kernel.ServerModuleProvider
            with build.jenesis.repository.cleanup.web.MaintenanceWebModule;
}
