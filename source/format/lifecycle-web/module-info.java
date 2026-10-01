/**
 * The version-lifecycle HTTP surface as a removable server module: a
 * {@link build.jenesis.repository.server.kernel.ServerModuleProvider} the server imports by {@code ServiceLoader}. A
 * thin Spring adapter over the {@link build.jenesis.repository.format.lifecycle.Lifecycle} marks and the discovered
 * {@link build.jenesis.repository.audit.AuditTrail}, resolved per tenant through {@code Repositories}, every route
 * under {@code /api/} and gated {@code manage:read}/{@code manage:write} by the security chain. Without it,
 * {@code /api/lifecycle} is a {@code 404}. Open so Spring can reflect over the controller.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.format.lifecycle.web {
    // The service the API answers from, for the console page that answers from it too.
    exports build.jenesis.repository.format.lifecycle.web;
    requires build.jenesis.repository.server.kernel;
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.audit;
    requires build.jenesis.repository.format.lifecycle;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.store;
    requires jakarta.servlet;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.server.spi;
    provides build.jenesis.repository.server.kernel.ServerModuleProvider
            with build.jenesis.repository.format.lifecycle.web.LifecycleWebModule;
    // Which formats a mark may be placed on, so a surface offers the action only where it will be seen.
    provides build.jenesis.repository.server.spi.CapabilityContributor
            with build.jenesis.repository.format.lifecycle.web.LifecycleCapabilityContributor;
}
