/**
 * The deployment-config management HTTP surface as a removable server feature module: it provides
 * {@link build.jenesis.repository.server.kernel.ServerModuleProvider}, so the repository server imports its configuration
 * through {@code ServiceLoader} discovery and names no settings, repository-definition, format-upstream or
 * upstream-credential endpoint. A thin Spring {@code web} adapter over the store-backed
 * {@link build.jenesis.repository.server.kernel.Settings} (the runtime-editable settings catalogue and the
 * {@code repositories.*} / {@code format-upstream.*} maps, applied live through
 * {@link build.jenesis.repository.server.kernel.LiveConfig} where a setting allows it) and the discovered
 * {@link build.jenesis.repository.upstream.UpstreamCredentialSource} (the write-only per-host proxy credentials), each
 * resolved per tenant through {@code Repositories}. Every route is under {@code /api/} and is gated
 * {@code manage:read}/{@code manage:write} by the security chain before it is reached - these are deployment-wide
 * knobs, so operator-tenant-only; with no upstream-credential module installed the {@code /api/upstreams/auth}
 * endpoints answer {@code 501}, and with this module absent the server carries none of the config surface and the
 * console hides its panels. Open so Spring can reflect over the controller and its configuration.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.config.web {
    exports build.jenesis.repository.config.web to build.jenesis.repository.server.kernel.test;
    requires build.jenesis.repository.server.kernel;
    requires build.jenesis.repository.server;
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
