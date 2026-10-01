/**
 * The console store's API twins as a removable server module: the key-header routes reaching the same {@code ui-store}
 * services the console's screens do - a tenant's SCIM token, the build-cache projects and their eviction, the tenants,
 * an artifact's origin trail and a folder's children - so what an operator can click can also be scripted. It provides
 * {@link build.jenesis.repository.server.kernel.ServerModuleProvider}, so the server names none of these endpoints.
 * Open so Spring can reflect over the controllers.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.console.api {
    requires build.jenesis.repository.store;
    exports build.jenesis.repository.console.api;
    requires build.jenesis.repository.server.kernel;
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.audit;
    requires build.jenesis.repository.ui.store;
    requires build.jenesis.repository.ui;
    requires build.jenesis.repository.scope;
    requires build.jenesis.repository.walk;
    requires jakarta.servlet;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    provides build.jenesis.repository.server.kernel.ServerModuleProvider
            with build.jenesis.repository.console.api.ConsoleApiModule;
}
