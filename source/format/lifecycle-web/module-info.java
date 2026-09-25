/**
 * The version-lifecycle HTTP surface as a removable server feature module: it provides
 * {@link build.jenesis.repository.server.kernel.ServerModuleProvider}, so the repository server imports its configuration
 * through {@code ServiceLoader} discovery and names no lifecycle endpoint. A thin Spring {@code web} adapter over the
 * framework-free {@link build.jenesis.repository.format.lifecycle.Lifecycle} deprecate/yank marks (small per-tenant
 * metadata objects written through the repository's scoped store) and the discovered
 * {@link build.jenesis.repository.audit.AuditTrail} that records the privileged mutations, each resolved per tenant
 * through {@code Repositories}. Every route is under {@code /api/} and is gated
 * {@code manage:read}/{@code manage:write} by the security chain before it is reached; with this module absent the
 * server carries none of the lifecycle surface and {@code /api/lifecycle} degrades to {@code 404}. Open so Spring can
 * reflect over the controller and its configuration.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.format.lifecycle.web {
    exports build.jenesis.repository.format.lifecycle.web to build.jenesis.repository.server.kernel.test;
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
