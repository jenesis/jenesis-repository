/**
 * The staging HTTP surface, contributed through {@link build.jenesis.repository.server.kernel.ServerModuleProvider}: a
 * thin adapter over the {@link build.jenesis.repository.staging.Staging} lifecycle, resolved per tenant and repository
 * through {@code Repositories}. Open so Spring can reflect over the controller and its configuration.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.staging.web {
    exports build.jenesis.repository.staging.web;
    requires build.jenesis.repository.server.kernel;
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.audit;
    requires build.jenesis.repository.staging;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires jakarta.servlet;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    provides build.jenesis.repository.server.kernel.ServerModuleProvider
            with build.jenesis.repository.staging.web.StagingModule;
}
