package build.jenesis.repository.closure;

import module java.base;
import build.jenesis.repository.maintenance.StorageNamespace;

/**
 * The closure module's storage manifest: the per-repository {@code closure} space - the closure pass's cadence stamps
 * ({@code closure/resolve-*}, {@code closure/reconcile-*}) and the {@link ReliedOn} rows under
 * {@code closure/relied-on} - so the orphan diagnostic and the operator purge know the key-space. The closures
 * themselves are sections of the version documents, under the metadata store's own space. Per-tenant, never shared.
 */
public final class ClosureStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> repositoryPrefixes() {
        return Set.of("closure");
    }
}
