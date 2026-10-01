package build.jenesis.repository.auth.keylogin;

import module java.base;

import build.jenesis.repository.maintenance.StorageNamespace;

/**
 * The key-login module's storage manifest: the issued-key index ({@link KeyLoginKeys#FILE}) and the first-run keys
 * ({@link FirstRunKey#SPACE}). Deployment-global by design - a login key spans tenants - under the {@code auth} space
 * beside the membership map, so the orphan diagnostic and the operator purge know it.
 */
public final class KeyLoginStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> sharedPrefixes() {
        return Set.of(KeyLoginKeys.FILE, FirstRunKey.SPACE);
    }
}
