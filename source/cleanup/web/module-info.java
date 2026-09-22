/**
 * The repository-maintenance HTTP surface as a removable server feature module: it provides
 * {@link build.jenesis.repository.server.kernel.ServerModuleProvider}, so the repository server imports its configuration
 * through {@code ServiceLoader} discovery and names no retention, cleanup or pin endpoint. A thin Spring {@code web}
 * adapter over the framework-free retention engine ({@link build.jenesis.repository.cleanup.RetentionSweeper}) and the
 * repository inventory ({@link build.jenesis.repository.inventory.StoreRepositoryInventory}), both resolved per
 * tenant-and-repository through {@code Repositories}: the {@code MaintenanceController} sweeps and plans
 * cleanups, reads and sets the retention policy, and pins or unpins a coordinate against garbage collection. With no
 * retention module installed the cleanup and retention endpoints answer {@code 501}; with this module absent the
 * server carries none of the surface and the console hides the panels. Open so Spring can reflect over the controller
 * and its configuration.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.cleanup.web {
    exports build.jenesis.repository.cleanup.web to build.jenesis.repository.server.kernel.test;
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
