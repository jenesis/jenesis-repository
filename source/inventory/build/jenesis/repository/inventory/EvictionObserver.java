package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.metadata.MetadataDocument;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A plug-in keeping per-version state outside the version's own document - rows elsewhere that name it - told as an
 * eviction destroys a version, while its document can still be read: what a plug-in keeps inside the document goes
 * with it, and what it keeps outside would otherwise stay behind naming a version that is gone.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> One instance serves every eviction, concurrently for different versions, and keeps no
 *       state between calls.</li>
 *   <li><b>Ordering.</b> Called once per eviction, after the version's pointers are unpublished and before its
 *       document is deleted, with that document as it stood.</li>
 *   <li><b>Idempotency / replay.</b> An eviction retried after a crash calls it again with what the document then
 *       holds, which may be nothing; what it does must hold up to being done twice.</li>
 *   <li><b>Error visibility.</b> A failure is logged and the eviction completes: what an observer keeps outside the
 *       document is an accelerator its own reconcile repairs, and an eviction left half done would leave a version
 *       evicted but serving.</li>
 *   <li><b>Tenant scoping.</b> The store is the evicting repository's, already scoped; an observer reads and writes
 *       nothing outside it, and leaves what another repository holds to a pass that can reach it.</li>
 *   <li><b>Bounded work.</b> Point reads and writes in proportion to what the document names, never a listing.</li>
 *   <li><b>Lifecycle / ownership.</b> {@link #installed()} discovers the observers once and holds them, since every
 *       eviction asks.</li>
 * </ol>
 */
public interface EvictionObserver {

    /** {@code version} of {@code coordinate} in {@code ecosystem} is being evicted from {@code repository}, its
     *  {@code document} about to be deleted. */
    void evicting(ArtifactStore repository, String ecosystem, String coordinate, String version,
                  MetadataDocument document) throws IOException;

    /** The installed observers, discovered once. */
    static List<EvictionObserver> installed() {
        return Installed.OBSERVERS;
    }

    /** The holder of {@link #installed()}'s answer. */
    final class Installed {

        private static final List<EvictionObserver> OBSERVERS = ServiceLoader.load(EvictionObserver.class).stream()
                .map(ServiceLoader.Provider::get).toList();

        private Installed() {
        }
    }
}
