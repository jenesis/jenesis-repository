package build.jenesis.repository.cleanup;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Providers;

/**
 * Removing one version because a client asked through its own protocol - a registry's manifest or tag {@code DELETE} -
 * through the same eviction retention uses ({@link RepositoryInventory#evict}), so it unpublishes the same pointers, is
 * observed the same way and leaves the same blobs to the collector. A format reaches it through this seam because it
 * may not require the store-backed inventory, which provides it.
 *
 * <p>Only the keys given up for a version with no pointer of its own differ: a client naming a manifest by digest asks
 * for the record that makes it one, which a retention pass must never take, since an index a live tag serves may name
 * that manifest. The layout says which keys each asks for.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> One instance serves every request thread and keeps no state between calls.</li>
 *   <li><b>Idempotency / replay.</b> Removing a version already gone succeeds and removes nothing, so a retried
 *       {@code DELETE} converges.</li>
 *   <li><b>Absence sentinel.</b> {@link #installed()} answers {@link #NONE} when no inventory is installed; it reports
 *       {@link #supported()} {@code false}, and a format answers a removal as unsupported rather than deleting around
 *       the inventory. {@code null} is never returned.</li>
 *   <li><b>Selection failure.</b> No selection key: two installed providers throw at resolution.</li>
 *   <li><b>Tenant scoping.</b> The caller hands in the repository's scoped store; nothing outside it is read or
 *       deleted.</li>
 *   <li><b>Error visibility.</b> A removal no installed format can place throws, touching nothing, as the eviction
 *       does.</li>
 *   <li><b>Lifecycle / ownership.</b> {@link #installed()} re-runs discovery on every call, so a request-path caller
 *       holds the answer.</li>
 *   <li><b>Bounded work.</b> A removal costs one version's eviction, which the owning layout bounds.</li>
 *   <li><b>Durability.</b> A removal stopped part way leaves what a crashed eviction leaves, which the reconcile pass
 *       converges; a pinned version is never removed.</li>
 * </ol>
 */
public interface VersionRemoval {

    /** No inventory is installed: nothing can be removed through the one path, so nothing is. */
    VersionRemoval NONE = new VersionRemoval() {
        @Override
        public boolean supported() {
            return false;
        }

        @Override
        public boolean pinned(ArtifactStore store, String ecosystem, String coordinate, String version) {
            return false;
        }

        @Override
        public void remove(ArtifactStore store, String ecosystem, String coordinate, String version)
                throws IOException {
            throw new IOException("no inventory is installed, so " + ecosystem + " " + coordinate + ":" + version
                    + " cannot be removed");
        }
    };

    /** Whether removals are carried out at all; {@code false} for {@link #NONE} alone. */
    default boolean supported() {
        return true;
    }

    /** Whether an operator pinned the version, which a client's removal is refused for. */
    boolean pinned(ArtifactStore store, String ecosystem, String coordinate, String version) throws IOException;

    /** Remove the version through the eviction, unless it is pinned, in which case nothing is touched. */
    void remove(ArtifactStore store, String ecosystem, String coordinate, String version) throws IOException;

    /** The installed removal, or {@link #NONE}. */
    static VersionRemoval installed() {
        return Providers.singleton("version-removal", ServiceLoader.load(VersionRemoval.class)).orElse(NONE);
    }
}
