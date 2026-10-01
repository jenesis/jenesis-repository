/**
 * The background-maintenance contracts: the SPI a module implements to run a recurring pass over the deployment's
 * repositories and the per-repository and per-tenant context the server's scheduler hands it, beside the per-module
 * storage manifest ({@code StorageNamespace}) and the explicit, dry-run-first operator purge over it
 * ({@code StorageNamespaces}).
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.maintenance {
    requires transitive build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.scope;
    exports build.jenesis.repository.maintenance;
    uses build.jenesis.repository.maintenance.MaintenanceTaskProvider;
    uses build.jenesis.repository.maintenance.StorageNamespace;
    provides build.jenesis.repository.maintenance.StorageNamespace
            with build.jenesis.repository.maintenance.ManifestStorageNamespace;

    // Transitive: a provider overriding IconContributor.icon() names IconResource in its signature.
    requires transitive build.jenesis.repository.icon;
}
