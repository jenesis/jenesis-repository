/**
 * The credential and authorization management HTTP surface as a removable server feature module, a
 * {@link build.jenesis.repository.server.kernel.ServerModuleProvider} the server discovers: a Spring {@code web}
 * adapter over the framework-free {@link build.jenesis.repository.server.spi.Authorization} (credentials, grants,
 * lifetime policy, quota, rate ceiling, OIDC trusts, roles) and the discovered
 * {@link build.jenesis.repository.audit.AuditTrail}, with its admin peers - the {@code /api/token} exchange, the
 * {@code /api/admin/spi} catalogue over {@link build.jenesis.repository.observation.SpiCatalog}, and
 * {@code /api/admin/orphans} and {@code /api/admin/purge} over
 * {@link build.jenesis.repository.maintenance.StorageNamespaces}. Every route is gated
 * {@code manage:read}/{@code manage:write} by the security chain (the token routes authenticate their own callers);
 * without the rate-limit or audit module those endpoints answer {@code 501}. Open for Spring's reflection.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.management.web {
    exports build.jenesis.repository.management.web;
    requires build.jenesis.repository.server.kernel;
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.audit;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.observation;
    requires build.jenesis.repository.posture;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.walk.task;
    requires jakarta.servlet;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    provides build.jenesis.repository.server.kernel.ServerModuleProvider
            with build.jenesis.repository.management.web.ManagementWebModule;
}
