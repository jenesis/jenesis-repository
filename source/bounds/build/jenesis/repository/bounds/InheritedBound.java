package build.jenesis.repository.bounds;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The bound an SPI's inherited default carries when it answers a paged or streaming question by materialising a
 * whole-list sibling, and the visible refusal past it.
 *
 * <p>Such a default emits the right rows while buffering everything the sibling answers - the opposite of what its
 * signature promises - and since every shipped implementation overrides it, its cost stays invisible until a plug-in
 * inherits it over a deployment-sized set. So, as on {@code ArtifactStore.page}, the default stays for small
 * implementations and fails visibly past a ceiling: this is that rule as one call, with one message, for every SPI.
 *
 * <p>The ceiling is {@link ArtifactStore#MAX_INHERITED_CHILDREN}: far above what an in-memory implementation holds, far
 * below what would exhaust the heap.
 *
 * <p>It bounds the implementation strategy, not the request: when it refuses, the rows are already in heap. What it
 * prevents is a deployment silently depending on that cost, paid again by every page, render and export; the remedy is
 * always to override the leg with a bounded read. It throws rather than truncating because the cost is already paid and
 * a short answer would read as a drained collection - a wrong answer in the vocabulary of completeness.
 */
public final class InheritedBound {

    private InheritedBound() {
    }

    /**
     * {@code rows} unchanged when the inherited default may serve them, else an {@link IllegalStateException} naming
     * the inheriting implementation, the leg, the sibling it answered over, the row count and the override that fixes
     * it.
     *
     * @param implementation the object whose class inherited the default - its class name is what the operator sees
     * @param inherited the inherited leg, written as its signature ({@code "all(Filter, int, int)"})
     * @param sibling the whole-list sibling the default materialised ({@code "all(Filter)"})
     * @param rows what the sibling answered, handed straight back, so the check costs nothing beyond its size
     * @throws IllegalStateException when {@code rows} holds more than {@link ArtifactStore#MAX_INHERITED_CHILDREN}
     */
    public static <T, C extends Collection<T>> C bounded(Object implementation, String inherited, String sibling,
                                                        C rows) {
        if (rows.size() > ArtifactStore.MAX_INHERITED_CHILDREN) {
            throw new IllegalStateException(implementation.getClass().getName() + " answers " + inherited
                    + " by materialising " + sibling + "'s " + rows.size() + " rows, past the "
                    + ArtifactStore.MAX_INHERITED_CHILDREN + "-row bound on the inherited default. Override "
                    + inherited + " with this implementation's own bounded read; a paged or streaming answer that "
                    + "first buffers the whole collection is not one.");
        }
        return rows;
    }
}
