package build.jenesis.repository.maintenance;

import module java.base;

/**
 * The maintenance module's own storage manifest: the per-module manifest documents under
 * {@code config/namespaces/<module>} are themselves persisted data with an owner, so the manifest accounts for
 * its own key-space rather than leaving it undeclared. It lives under one of the two legitimately shared
 * (deployment-global) roots - the superadmin {@code config/} root - because a manifest entry must
 * outlive any tenant and any module removal to keep naming the orphaned data it describes.
 */
public final class ManifestStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> sharedPrefixes() {
        return Set.of(StorageNamespaces.ROOT);
    }
}
