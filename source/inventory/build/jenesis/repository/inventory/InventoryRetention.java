package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.cleanup.RetentionPolicy;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The retention policy a repository may hold as a small {@link #KEY} properties object rather than as repository
 * settings; nothing in the product writes one. The one-time move of each such policy into its repository's settings
 * reads it through here ({@code StoreRepositoryInventory.formerRetention}) and leaves it in place - nothing deletes
 * data automatically. A stored policy that cannot be parsed fails loudly rather than reading as "no policy" - silence
 * around a deletion policy is exactly what an operator must never get.
 *
 * <p><strong>The key sits at repository scope, where the data does.</strong> Under {@code retention} it is an ordinary
 * owned prefix that {@link InventoryStorageNamespace} declares beside {@code identity} and {@code sizes}, so purging
 * the inventory module reclaims every repository's deletion policy instead of leaving it behind.
 */
final class InventoryRetention {

    /** The per-repository key this policy is stored under, relative to the repository-scoped store. */
    static final String KEY = "retention";

    private final ArtifactStore store;

    InventoryRetention(ArtifactStore store) {
        this.store = store;
    }

    /** The retention policy stored for this repository, or empty if none has been set. A stored policy that cannot
     *  be parsed fails loudly (a contained {@link IOException}) rather than reading as "no policy". */
    Optional<RetentionPolicy> readRetention() throws IOException {
        Optional<ArtifactStore.Versioned> config = store.readVersioned(KEY);
        if (config.isEmpty()) {
            return Optional.empty();
        }
        try {
            Properties values = new Properties();
            values.load(new ByteArrayInputStream(config.get().content()));
            return Optional.of(RetentionPolicy.parse(values.getProperty("keepLast"), values.getProperty("maxAge"),
                    values.getProperty("prereleaseExpiry"), values.getProperty("notDownloadedFor")));
        } catch (RuntimeException e) {
            throw new IOException("corrupt retention policy at " + KEY, e);
        }
    }
}
