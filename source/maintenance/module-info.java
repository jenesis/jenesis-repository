/**
 * The background-maintenance contracts: the {@code ServiceLoader} SPI a module implements to run a recurring pass
 * over the deployment's repositories (a retention sweep, a vulnerability re-scan), and the per-repository and
 * per-tenant context the neutral scheduler hands it. The server hosts one scheduler owning the thread, the tenant
 * iteration, the single-writer lease and the gauges; a pass is a drop-in module, so installing or removing it adds
 * or removes the background work with no change to neutral code. Beside the passes sits the per-module storage
 * manifest ({@code StorageNamespace}, a module's declaration of the store key-spaces it owns - per-tenant by
 * default, shared only under the auth/config roots) and the explicit operator purge over it
 * ({@code StorageNamespaces}) - never automatic, dry-run first, target named explicitly. The module declares its
 * own manifest space ({@code config/namespaces}), so the manifest accounts for itself, and it names the reserved
 * roots the purge deliberately cannot reach ({@code StorageNamespaces.UNREACHABLE}, derived from the scope module's
 * reserved set) so an operator meets that exclusion on the purge surface itself.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.maintenance {
    requires transitive build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.settings;
    // The one rule naming the store root's reserved key spaces, read rather than restated: StorageNamespaces derives
    // the roots its purge can never reach from Scopes.SPACES minus the two SHARED_ROOTS a module may declare under,
    // so the two sets cannot drift into disagreement.
    requires build.jenesis.repository.scope;
    exports build.jenesis.repository.maintenance;
    uses build.jenesis.repository.maintenance.MaintenanceTaskProvider;
    uses build.jenesis.repository.maintenance.StorageNamespace;
    provides build.jenesis.repository.maintenance.StorageNamespace
            with build.jenesis.repository.maintenance.ManifestStorageNamespace;

    // The family extends IconContributor, so every implementation gains the optional mark seam and
    // the console resolves one answer for all of them. Transitive: an implementation overriding
    // icon() names IconResource in its own signature.
    requires transitive build.jenesis.repository.icon;
}
