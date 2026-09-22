/**
 * The credential and authorization management HTTP surface as a removable server feature module: it provides
 * {@link build.jenesis.repository.server.kernel.ServerModuleProvider}, so the repository server imports its configuration
 * through {@code ServiceLoader} discovery and names no policy, quota, rate-limit, trust, role, audit,
 * token-exchange, SPI-catalogue or storage-purge endpoint. A thin Spring {@code web} adapter over the framework-free
 * {@link build.jenesis.repository.server.spi.Authorization} (the tenant's credentials, grants, lifetime policy, quota
 * ceiling, rate ceiling, OIDC trusts and named roles) and the discovered {@link build.jenesis.repository.audit.AuditTrail}
 * (the queryable, CSV-exportable trail of privileged mutations), each resolved per tenant through the
 * {@code Repositories}; the module also carries the admin peers re-homed beside it - the OIDC {@code /api/token}
 * exchange, the {@code /api/admin/spi} plug-in catalogue read over {@link build.jenesis.repository.observation.SpiCatalog},
 * and the {@code /api/admin/orphans} + {@code /api/admin/purge} storage-manifest reclamation over
 * {@link build.jenesis.repository.maintenance.StorageNamespaces}. Every route is under {@code /api/} and is gated
 * {@code manage:read}/{@code manage:write} by the security chain before it is reached (the token and leaked routes
 * authenticate their own callers); with no rate-limit module installed the rate-limit endpoints answer {@code 501} and
 * with no audit module the audit endpoints answer {@code 501}, and with this module absent the server carries none of
 * the management surface and the console hides its panels. Open so Spring can reflect over the controllers and their
 * configuration.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.management.web {
    exports build.jenesis.repository.management.web to build.jenesis.repository.server.kernel.test;
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
