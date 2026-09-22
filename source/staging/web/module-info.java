/**
 * The staging HTTP surface as a removable server feature module: it provides
 * {@link build.jenesis.repository.server.kernel.ServerModuleProvider}, so the repository server imports its
 * configuration through {@code ServiceLoader} discovery and names no staging endpoint. A thin Spring {@code web}
 * adapter over the framework-free {@link build.jenesis.repository.staging.Staging} lifecycle (resolved per
 * tenant-and-repository through {@code Repositories}); with this module absent the server carries no
 * staging routes and the console hides the staging surface. Open so Spring can reflect over the controller and its
 * configuration.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.staging.web {
    exports build.jenesis.repository.staging.web to build.jenesis.repository.server.kernel.test;
    requires build.jenesis.repository.server.kernel;
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.audit;
    requires build.jenesis.repository.staging;
    requires jakarta.servlet;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    provides build.jenesis.repository.server.kernel.ServerModuleProvider
            with build.jenesis.repository.staging.web.StagingModule;
}
