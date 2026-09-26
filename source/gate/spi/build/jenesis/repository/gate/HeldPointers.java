package build.jenesis.repository.gate;

import module java.base;

import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.walk.PagedTreeWalk;
import build.jenesis.repository.walk.Traversal;

/**
 * The one descent over the live {@code publish/quarantine} review-pointer subtree, shared by the review queue
 * ({@code QuarantineLog.heldPaths}) and the cross-alias withhold guard ({@code HoldLifecycle.withheldByAnotherAlias}).
 * Both previously carried their own self-recursive descent that listed each level whole with
 * {@link ArtifactStore#list} - the unbounded-work class the bounded traversal primitives exist to remove - and both had to answer the same
 * awkward question about this particular tree, so the answer is written once here rather than twice.
 *
 * <p><strong>Why this is not simply {@link PagedTreeWalk} applied to the root.</strong> The shared bounded tree walk
 * defines a <em>leaf</em> as a key {@link ArtifactStore#exists} answers for, and it does not descend into one. A hold
 * pointer is a blob at {@code publish/quarantine<path>}, and on a flat/object store a held path can be a proper prefix
 * of another held path - both are independent keys - so a node here can be a pointer <em>and</em> a container at once.
 * Handing the root straight to the tree walk would deliver such a node and then silently drop every deeper hold
 * beneath it, which is the mirror image of the "leaf only" defect the review queue was fixed for. This class therefore
 * drives the shared primitives rather than replacing them: the tree walk enumerates each subtree, and the rare node
 * that is both a pointer and a container is re-queued as a fresh subtree root, so <em>every</em> stored pointer is
 * delivered exactly once and no level is ever listed whole.
 *
 * <p><strong>Bounds.</strong> Every bound is the shared primitives' own. The per-call entry cap is a continuation this
 * class follows to exhaustion - a review queue that stopped early would report a hold-free repository that still holds
 * artifacts, and an alias guard that stopped early would clear a withhold marker a still-held sibling needs, so a
 * short answer here is a wrong answer, not a page of a right one. What actually bounds the descent is the step budget
 * (one {@link ArtifactStore#exists} probe per opened node) and the depth ceiling, both of which raise a named
 * {@link build.jenesis.repository.walk.TraversalException} rather than answering short.
 */
final class HeldPointers {

    private HeldPointers() {
    }

    /** The review-pointer subtree root: a hold on request path {@code /p} is a pointer stored at {@code ROOT + "/p"}. */
    static final String ROOT = Publication.QUARANTINE_ROOT;

    /** One stored pointer key under {@link #ROOT}, delivered in {@linkplain build.jenesis.repository.walk.Trees#order
     *  path order}. The root itself is never delivered (it carries no request path). */
    @FunctionalInterface
    interface Pointers {

        /** Handle one pointer key; answer {@code false} to end the descent immediately (the alias guard's early exit
         *  on the first live alias found). */
        boolean accept(String key) throws IOException;
    }

    /** The subtree bounds. The step budget is what really bounds this descent - the entry cap is followed to
     *  exhaustion by {@link #descend} - and depth stays at the primitive's {@link ArtifactStore#MAX_SEGMENTS} default,
     *  so a pointer deeper than any the store accepts fails <em>by name</em> rather than being skipped. */
    private static final PagedTreeWalk SUBTREE = PagedTreeWalk.bounded().steps(1_000_000);
    /** The same walk at the drain width, for the two descents that follow their continuation to exhaustion: a
     *  filesystem rescans a container per page, so for a drain the page width is the number of rescans of a
     *  wide level. The review screen's one window keeps {@link #SUBTREE}, whose one readdir is the same at any
     *  width and whose page is what the screen renders. */
    private static final PagedTreeWalk DRAIN = SUBTREE.page(BoundedChildren.DRAIN_PAGE);

    /** A one-name existence probe: does this pointer key also parent deeper keys? Not a traversal - it asks for a
     *  single child and stops - so it drives the flat primitive at its narrowest rather than opening a descent. */
    private static final BoundedChildren PROBE = BoundedChildren.bounded().entries(1).page(1).steps(1);

    /** The children of a node that is a pointer <em>and</em> a container, each queued as a fresh subtree root. Only
     *  that pathological shape is ever buffered, so the queue is not a level listing in disguise; the entry cap stays
     *  the primitive's default, which truncates - and a truncation here would drop holds, so it is refused loudly. */
    private static final BoundedChildren SPLIT = BoundedChildren.bounded();

    /**
     * Deliver every stored review pointer under {@link #ROOT} to {@code pointers}, in path order within each subtree,
     * until the tree is exhausted or the consumer stops it. Answers whether the tree was walked whole ({@code true})
     * or the consumer ended it early ({@code false}).
     */
    static boolean descend(ArtifactStore store, Pointers pointers) throws IOException {
        Deque<String> roots = new ArrayDeque<>();
        roots.push(ROOT);
        try {
            while (!roots.isEmpty()) {
                String root = roots.pop();
                List<String> split = new ArrayList<>();
                String cursor = null;
                while (true) {
                    Traversal.Result result = DRAIN.walk(store, root, cursor, key -> {
                        if (!ROOT.equals(key) && !pointers.accept(key)) {
                            throw STOP;
                        }
                        if (parents(store, key)) {
                            split.add(key);      // a pointer that is also a container: its subtree is walked next
                        }
                    });
                    if (result.exhausted()) {
                        break;
                    }
                    cursor = result.cursor().orElseThrow();
                }
                for (String node : split) {
                    Traversal.Result result = SPLIT.scan(store, node, name -> roots.push(Traversal.key(node, name)));
                    if (result.truncated()) {
                        throw new IOException("The quarantine review pointer '" + node + "' parents more than "
                                + SPLIT.entries() + " deeper holds; enumerating only the first would hide the rest "
                                + "from the review queue and from the cross-alias withhold guard");
                    }
                }
            }
        } catch (Stop _) {
            return false;
        }
        return true;
    }

    /** One bounded page of stored pointer keys and the key to continue from ({@code null} when the tree is
     *  exhausted). */
    record Page(List<String> keys, String next) {
    }

    /**
     * Deliver one page of review pointers: at most {@code limit} keys in path order, starting strictly after
     * {@code after} ({@code null} from the top), plus the deeper holds of any pointer-and-container node met within
     * the page, so a page never splits such a node's subtree and the next page's cursor is always a key the root walk
     * can resume from. A review screen reads the queue through this rather than through {@link #descend}: a
     * repository with tens of thousands of holds is a queue to page through, not a list to render.
     */
    static Page page(ArtifactStore store, String after, int limit) throws IOException {
        List<String> keys = new ArrayList<>();
        List<String> split = new ArrayList<>();
        String cursor = after == null || after.isEmpty() ? null : after;
        boolean more;
        try {
            while (true) {
                Traversal.Result result = SUBTREE.walk(store, ROOT, cursor, key -> {
                    if (ROOT.equals(key)) {
                        return;
                    }
                    keys.add(key);
                    if (parents(store, key)) {
                        split.add(key);
                    }
                    if (keys.size() > limit) {
                        throw STOP;                     // one past the page: the walk has told us there is more
                    }
                });
                if (result.exhausted()) {
                    more = false;
                    break;
                }
                cursor = result.cursor().orElseThrow();
            }
        } catch (Stop _) {
            more = true;
        }
        String next = more ? keys.get(limit - 1) : null;        // the last key delivered: the walk resumes after it
        List<String> page = new ArrayList<>(more ? keys.subList(0, limit) : keys);
        for (String node : split) {
            if (page.contains(node)) {
                descendBeneath(store, node, page::add);
            }
        }
        return new Page(List.copyOf(page), next);
    }

    private static void descendBeneath(ArtifactStore store, String node, Consumer<String> keys) throws IOException {
        Deque<String> roots = new ArrayDeque<>();
        Traversal.Result children = SPLIT.scan(store, node, name -> roots.push(Traversal.key(node, name)));
        if (children.truncated()) {
            throw new IOException("The quarantine review pointer '" + node + "' parents more than " + SPLIT.entries()
                    + " deeper holds; enumerating only the first would hide the rest from the review queue");
        }
        while (!roots.isEmpty()) {
            String root = roots.pop();
            String cursor = null;
            while (true) {
                Traversal.Result result = DRAIN.walk(store, root, cursor, key -> {
                    keys.accept(key);
                    if (parents(store, key)) {
                        descendBeneath(store, key, keys);
                    }
                });
                if (result.exhausted()) {
                    break;
                }
                cursor = result.cursor().orElseThrow();
            }
        }
    }

    /** Whether {@code key} - already known to be a stored pointer - also parents deeper keys. */
    private static boolean parents(ArtifactStore store, String key) throws IOException {
        boolean[] any = {false};
        PROBE.scan(store, key, _ -> any[0] = true);
        return any[0];
    }

    /** The consumer's early exit, an {@link IOException} because that is the cancellation hook {@link PagedTreeWalk}
     *  documents, caught immediately in {@link #descend} and never surfaced. Stackless and shared: it is control flow,
     *  not a failure. */
    private static final class Stop extends IOException {

        private static final long serialVersionUID = 1L;

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    private static final Stop STOP = new Stop();
}
