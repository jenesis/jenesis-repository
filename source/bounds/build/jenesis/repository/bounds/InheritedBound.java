package build.jenesis.repository.bounds;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The bound an SPI's <em>inherited</em> default carries when it answers a paged or streaming question by
 * materialising a whole-list sibling - and the visible refusal past it.
 *
 * <p><strong>The shape, and why it throws.</strong> A {@code default} that pages or streams over an abstract
 * whole-list sibling is correct and unbounded at once: it emits the right rows in the right order while buffering
 * everything the sibling can answer to do it, which is the opposite of what the paged or streaming signature
 * promises. Every shipped implementation overrides such a default - which is exactly why nothing catches it - so
 * its cost is invisible until a plug-in author inherits it over a deployment-sized ledger. The free core settled
 * this on {@code ArtifactStore.page}: keep the default, because a small implementation genuinely wants it,
 * and make it <em>fail visibly</em> at a stated ceiling instead of degrading silently. This is that ruling as one
 * call, so every SPI that copies the idiom refuses the same way with the same message rather than
 * hand-rolling its own check.
 *
 * <p><strong>One ceiling, read from the core.</strong> The number is
 * {@link ArtifactStore#MAX_INHERITED_CHILDREN}, not a second constant that could drift from it: it is deliberately
 * far above any collection a simple in-memory implementation legitimately holds and far below one that would
 * exhaust the heap, so it separates "this implementation never needed a bounded read" from "this implementation is
 * about to buffer the deployment's data to answer one page".
 *
 * <p><strong>What the bound is on.</strong> It bounds the <em>implementation strategy</em>, not the caller's
 * request: by the time it refuses, the sibling has already answered and the rows are in heap (exactly as
 * {@code ArtifactStore.pageByListing} lists before it refuses). What it prevents is a deployment coming to depend
 * on that cost silently - the next page, the next render and the next export pay it again, each time nearer the
 * heap. So the remedy it names is always the same one: override the leg with the implementation's own bounded read.
 *
 * <p><strong>Why it throws rather than truncating.</strong> The truncate-or-throw asymmetry this codebase draws is
 * that a bound with a continuation may end a read as a value, and a bound without one may not. Truncating here
 * would have neither half: the whole cost is already paid when the check runs, so a short answer would hide the
 * defect rather than bound it, and it would read to the caller as a drained collection - a wrong answer in the
 * vocabulary of completeness. A refusal names the implementation, the leg, the size and the fix.
 */
public final class InheritedBound {

    private InheritedBound() {
    }

    /**
     * {@code rows} unchanged when the inherited default may serve them, or {@link IllegalStateException} naming the
     * implementation that inherited the default, the leg it answered, the whole-list sibling it answered over, the
     * row count and the override that fixes it.
     *
     * @param implementation the object whose class inherited the default - its class name is what the operator sees
     * @param inherited      the inherited leg, written as its signature ({@code "all(Filter, int, int)"})
     * @param sibling        the whole-list sibling the default materialised ({@code "all(Filter)"})
     * @param rows           what the sibling answered - handed straight back, never copied, so the check costs
     *                       nothing beyond the size the sibling already knows
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
