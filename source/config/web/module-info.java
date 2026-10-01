/**
 * The deployment-config management HTTP surface as a removable server module: a
 * {@link build.jenesis.repository.server.kernel.ServerModuleProvider} the server imports by {@code ServiceLoader}. A
 * thin Spring adapter over the store-backed {@link build.jenesis.repository.server.kernel.Settings} (the runtime
 * catalogue and the {@code repositories.*} and {@code format-upstream.*} maps, applied live through
 * {@link build.jenesis.repository.server.kernel.LiveConfig} where a setting allows) and the discovered
 * {@link build.jenesis.repository.upstream.UpstreamCredentialSource}. Every route is under {@code /api/} and gated
 * {@code manage:read}/{@code manage:write} by the security chain, operator tenant only. Open so Spring can reflect over
 * the controller.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.config.web {
    exports build.jenesis.repository.config.web;
    requires build.jenesis.repository.server.kernel;
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.audit;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.definitions;
    requires build.jenesis.repository.upstream;
    requires jakarta.servlet;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    provides build.jenesis.repository.server.kernel.ServerModuleProvider
            with build.jenesis.repository.config.web.ConfigWebModule;
}
