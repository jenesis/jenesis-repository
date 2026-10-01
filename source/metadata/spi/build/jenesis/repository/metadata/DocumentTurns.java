package build.jenesis.repository.metadata;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Writers of one metadata document on one node take turns rather than race.
 *
 * <p>A version's document is written by every publish of that version - its published section, origin row, licences -
 * and rivals publishing one release at once each run a read-transform-compare-and-set on it. Only one wins each round,
 * so with more rivals than the store's compare-and-set tries the rest would fail with a {@code 500}. A per-document
 * turn leaves the compare-and-set only the other nodes' writers to arbitrate.
 *
 * <p>Every writer of the document takes it - the section mutate and the inventory's publish and licence writes - since
 * a turn only some take orders nothing. A turn wraps the compare-and-set alone, and the transform inside is pure, so no
 * holder asks for a second turn. Striped, so the set is bounded; two documents sharing a stripe only wait for each
 * other.
 */
public final class DocumentTurns {

    private static final ReentrantLock[] STRIPES = new ReentrantLock[256];

    static {
        for (int stripe = 0; stripe < STRIPES.length; stripe++) {
            STRIPES[stripe] = new ReentrantLock();
        }
    }

    private DocumentTurns() {
    }

    /** One write of a document, in its turn among this node's writers of the same document. */
    @FunctionalInterface
    public interface Write<T> {

        T run() throws IOException;
    }

    /** Run {@code write} on {@code key} of {@code store} once this node's other writers of that document are done. */
    public static <T> T take(ArtifactStore store, String key, Write<T> write) throws IOException {
        ReentrantLock stripe = STRIPES[Math.floorMod(Objects.hash(store.identity(), key), STRIPES.length)];
        stripe.lock();
        try {
            return write.run();
        } finally {
            stripe.unlock();
        }
    }
}
