package build.jenesis.repository.metadata;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Writers of one metadata document on one node take their turn rather than race.
 *
 * <p>A version's document is written by every publish of that version - its published section, its origin row, its
 * licences - and rivals publishing one release at once (a CI job and its retries, a fan-out of builds) each run a
 * read-transform-compare-and-set on it together. Only one of them can win each round, so with more rivals than the
 * store's compare-and-set tries the rest ran out and their publishes failed with a {@code 500}; measured at
 * thirty-two rivals on one release through the containerised image. Taking a turn per document first leaves the
 * compare-and-set only the writers of other nodes to arbitrate, which is the contention it was sized for.
 *
 * <p>Every writer of the document takes it - the section-scoped mutate and the inventory's own publish and licence
 * writes alike - since a turn only one of them takes orders nothing. A turn wraps the compare-and-set and nothing
 * after it, and the transform inside is a pure function of the document, so no holder of one turn ever asks for
 * another. Striped rather than one lock per key, so the set is bounded however many documents a node writes; two
 * documents sharing a stripe wait for each other, which costs time and never correctness.
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
