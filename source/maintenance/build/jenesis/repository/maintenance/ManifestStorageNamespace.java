package build.jenesis.repository.maintenance;

import module java.base;

/**
 * The maintenance module's own storage declaration: the manifest documents under {@link StorageNamespaces#ROOT}. They
 * are deployment-global because an entry must outlive any tenant and any module to keep naming orphaned data.
 */
public final class ManifestStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> sharedPrefixes() {
        return Set.of(StorageNamespaces.ROOT);
    }
}
