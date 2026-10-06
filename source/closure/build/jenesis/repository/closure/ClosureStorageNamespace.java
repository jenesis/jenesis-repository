package build.jenesis.repository.closure;

import module java.base;
import build.jenesis.repository.maintenance.StorageNamespace;

/**
 * The closure module's storage manifest: the per-repository {@code closure} space - the closure pass's cadence stamps
 * ({@code closure/resolve-*}, {@code closure/reconcile-*}), the {@link ReliedOn} rows under
 * {@code closure/relied-on}, the declared dependents' rows under {@value DeclaredRows#ROOT} and the closures its versions
 * gave up, under {@code closure/retired}, until the pass takes back their rows - and the tenant's {@value ReliedOn#SPACE} space, holding the rows of the packages closures
 * name by coordinate in another ecosystem and its reconcile's cadence stamps, so the orphan diagnostic and the
 * operator purge know the key-space. The closures themselves are sections of the version documents, under the metadata
 * store's own space. Per-tenant, never shared.
 */
public final class ClosureStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> repositoryPrefixes() {
        return Set.of("closure");
    }

    @Override
    public Set<String> tenantPrefixes() {
        return Set.of(ReliedOn.SPACE);
    }
}
