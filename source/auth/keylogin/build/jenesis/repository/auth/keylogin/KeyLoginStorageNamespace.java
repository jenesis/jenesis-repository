package build.jenesis.repository.auth.keylogin;

import module java.base;

import build.jenesis.repository.maintenance.StorageNamespace;

/**
 * The key-login module's storage manifest: it owns the issued-key index {@link KeyLoginKeys#FILE
 * KeyLoginKeys writes}. The space is shared (deployment-global) by design - a console login key spans tenants, so
 * its hash-to-principal map is root-level - and sits under the {@code auth/} root, the manifest's one legitimate
 * shared home for the credential/membership map beside the superadmin {@code config/}. Declaring it makes the index
 * a first-class registered namespace: the orphan diagnostic and the explicit operator purge know the key-space
 * without a hardcoded table, and the storage-scope rule blesses it explicitly rather than leaving it unaccounted.
 */
public final class KeyLoginStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> sharedPrefixes() {
        return Set.of(KeyLoginKeys.FILE);
    }
}
