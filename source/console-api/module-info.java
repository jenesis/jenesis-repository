/**
 * The console store's API twins as a removable server feature module: the key-header-authenticated routes that
 * reach the same {@code ui-store} services the admin console's screens do - a tenant's SCIM bearer token, the
 * build-cache projects and their eviction, and an artifact's origin trail - so a capability an operator can click
 * can also be scripted, run from CI and driven by the CLI. It provides
 * {@link build.jenesis.repository.server.kernel.ServerModuleProvider}, so the repository server imports its
 * configuration through {@code ServiceLoader} discovery and names none of these endpoints; they used to live in the
 * composition root beside the two routes that are the application's own. Open so Spring can reflect over the
 * controllers and their configuration.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.console.api {
    requires build.jenesis.repository.store;
    exports build.jenesis.repository.console.api to build.jenesis.repository.server.kernel.test;
    requires build.jenesis.repository.server.kernel;
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.audit;
    requires build.jenesis.repository.ui.store;
    requires build.jenesis.repository.ui;
    requires jakarta.servlet;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    provides build.jenesis.repository.server.kernel.ServerModuleProvider
            with build.jenesis.repository.console.api.ConsoleApiModule;
}
