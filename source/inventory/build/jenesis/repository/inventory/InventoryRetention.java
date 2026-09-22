package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.cleanup.RetentionPolicy;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The retention-policy storage subsystem extracted from {@link StoreRepositoryInventory}: this repository's
 * {@link RetentionPolicy} persisted as a small {@link #KEY} properties object. A stored policy that cannot
 * be parsed fails loudly rather than reading as "no policy" - silence around a deletion policy is exactly what an
 * operator must never get. The facade owns the seam - {@code readRetention}/{@code writeRetention} delegate here - and
 * this class shares the facade's compare-and-set {@code writeVersioned}.
 *
 * <p><strong>The key sits at repository scope, where the data does.</strong> It used to be
 * {@code config/retention} - a per-repository object spelled with the name of the deployment-global reserved root, so
 * it read like a shared claim while being nothing of the kind, and it sat outside {@link InventoryStorageNamespace}'s
 * declaration because no declaration could describe it: {@code config/} is not a per-repository space. Under
 * {@code retention} it is an ordinary owned prefix beside {@code downloaded}, {@code identity} and {@code sizes}, so
 * purging the inventory module reclaims every repository's deletion policy instead of leaving it behind.
 */
final class InventoryRetention {

    /** The per-repository key this policy is stored under, relative to the repository-scoped store. */
    static final String KEY = "retention";

    private final StoreRepositoryInventory inventory;
    private final ArtifactStore store;

    InventoryRetention(StoreRepositoryInventory inventory, ArtifactStore store) {
        this.inventory = inventory;
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

    /** Store this repository's retention policy. */
    void writeRetention(RetentionPolicy policy) throws IOException {
        Properties values = new Properties();
        values.setProperty("keepLast", Integer.toString(policy.keepLast()));
        if (policy.maxAge() != null) {
            values.setProperty("maxAge", policy.maxAge().toString());
        }
        if (policy.prereleaseExpiry() != null) {
            values.setProperty("prereleaseExpiry", policy.prereleaseExpiry().toString());
        }
        if (policy.notDownloadedFor() != null) {
            values.setProperty("notDownloadedFor", policy.notDownloadedFor().toString());
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        values.store(bytes, null);
        inventory.writeVersioned(KEY, bytes.toByteArray());
    }
}
