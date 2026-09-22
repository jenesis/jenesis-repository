/**
 * The repository server's shared runtime KERNEL (kernel/boot split): the per-tenant resolver
 * ({@code Repositories}), the runtime-config kernel ({@code Settings}, {@code LiveConfig}, {@code PinnedSettings},
 * {@code RepositoryProperties}, the settings contributors and the settings-refresh convergence pass), the
 * maintenance scheduling kernel ({@code MaintenanceScheduler}, {@code Lease}), the tenant-blind half of the
 * security kernel ({@code RequestBodyLimitFilter}, {@code RepositoryRequests}) and the {@code ServerModuleProvider}
 * discovery SPI the feature-web modules implement.
 *
 * <p><b>What is deliberately not here: any feature.</b> This module requires contract homes and the seams beneath
 * them - the server and its SPI, the store, the settings, maintenance and compliance contracts, the staging,
 * retention and upstream-credential SPIs, the blobs view and the metering decorator - and no implementation of
 * anything. It used to require the router, the gate, the inventory, the metadata store and the import SPI as well,
 * for six classes: {@code LiveConfig} parsed the router's definitions, {@code Repositories} handed out the gated
 * repository, the quarantine log and the inventory as one-line factories, {@code ReleaseImmutability} and
 * {@code DeployEdgeHooks} were the write edge's gate concerns. Measured 2026-09-20: every one of the 21 web
 * adapters requires this module, so each dragged seventeen modules through it while three quarters of
 * what they called was {@code tenant()} and {@code store()}. The definitions parser, the two edge classes and the
 * live definitions are the gateway's now, and the kernel asks a definition only the two questions it has
 * ({@code RepositoryDefinitions}). The toggle catalogue ({@code ModuleTogglesSettingsContributor}) stays: it
 * enumerates every provider family, but every family it names is a contract home this module requires anyway,
 * and a settings contributor's module names the document its values are stored in
 * ({@code config/settings/<module>.json}) - moving it would have stranded every toggle an operator ever wrote. A feature's factory is the feature's own: {@code new
 * StoreRepositoryInventory(store)}, {@code new QuarantineLog(store)}, {@code new GatedRepository(writable)}.
 *
 * <p><b>Nor tenancy.</b> The three multi-tenant routings ({@code multi}, {@code host},
 * {@code path}) and the enforcing {@code RepositoryAuthorizationManager} - the one that knows an operator tenant, a
 * path-routed repository segment and the tenant a key's usage is charged to - live in
 * {@code build.jenesis.repository.server.tenancy}, which requires this module and never the reverse. They used to be
 * seven classes in this package, and the split was measured before it was made: nothing here read them but a path
 * normaliser the manager carried, which moved to {@code RepositoryRequests} where both halves read it. The kernel is
 * therefore the part a single-tenant deployment is entirely served by, and the tenancy module the part that can be
 * left out of one - the routing is the fixed one and the authorization manager backs off to the tenancy
 * module's by bean name, so a composition without it is the plain chain over this kernel.
 *
 * <p>This module carries NO {@code @SpringBootApplication} and no controller: the boot root is
 * {@code build.jenesis.repository.application} (a leaf module nothing in the product requires), which composes these
 * kernel types behind Spring MVC and registers the kernel's Spring-visible beans explicitly - the 13+ feature-web
 * modules require only this kernel. Open so Spring can reflect over the kernel beans the application module
 * registers.
 *
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 * @jenesis.release 25
 *
 */
open module build.jenesis.repository.server.kernel {
    // Deliberately kept through the kernel/boot split: LicenseGraphTest's structural guard
    // requires EVERY source/* module to carry the licence module, the kernel included.
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.scope;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    // ProxyLeg.ALLOW_INTERNAL: the one proxy outbound dial, read by its declared constant.
    requires build.jenesis.repository.blobs;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.observation;
    requires build.jenesis.repository.store.metering;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.upstream;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.cleanup;
    requires build.jenesis.repository.staging;
    // The toggle catalogue enumerates the import sources among every other provider family.
    requires build.jenesis.repository.importer;
    requires jakarta.servlet;
    uses build.jenesis.repository.server.kernel.ServerModuleProvider;
    requires micrometer.observation;
    requires micrometer.core;
    requires org.slf4j;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    requires spring.boot;
    requires spring.security.core;
    requires spring.security.web;
    exports build.jenesis.repository.server.kernel;
    provides build.jenesis.repository.observation.ObservabilitySource
            with build.jenesis.repository.server.kernel.MaintenanceObservability;
    provides build.jenesis.repository.maintenance.StorageNamespace
            with build.jenesis.repository.server.kernel.SettingsStorageNamespace;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.server.kernel.BatchUploadSettingsContributor,
                    build.jenesis.repository.server.kernel.CachingSettingsContributor,
                    build.jenesis.repository.server.kernel.DemoSettingsContributor,
                    build.jenesis.repository.server.kernel.ImmutabilitySettingsContributor,
                    build.jenesis.repository.server.kernel.ModuleTogglesSettingsContributor;
}
