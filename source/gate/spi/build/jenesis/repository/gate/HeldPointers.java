package build.jenesis.repository.gate;

import module java.base;

import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.walk.PagedTreeWalk;
import build.jenesis.repository.walk.Traversal;

/**
 * The descent over the live {@code publish/quarantine} review-pointer subtree, shared by the review queue and the
 * cross-alias withhold guard ({@link HeldElsewhere}).
 *
 * <p>{@link PagedTreeWalk} does not descend into a key {@link ArtifactStore#exists} answers for, but a held path can be
 * a proper prefix of another held path, so a node here can be a pointer and a container at once. Such a node is
 * re-queued as a fresh subtree root, so every pointer is delivered exactly once and no level is listed whole.
 *
 * <p>The entry cap is followed to exhaustion, since a short answer would hide holds from the queue or let the guard
 * clear a marker a held sibling needs; the step budget and the depth ceiling raise a named
 * {@link build.jenesis.repository.walk.TraversalException} instead.
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

        /** Handles one pointer key; {@code false} ends the descent. */
        boolean accept(String key) throws IOException;
    }

    /** The subtree bounds: the step budget, and the default depth of {@link ArtifactStore#MAX_SEGMENTS}. */
    private static final PagedTreeWalk SUBTREE = PagedTreeWalk.bounded().steps(1_000_000);
    /** The same walk at the drain width, for the descents that run to exhaustion, since a filesystem rescans a
     *  container per page. The review screen's page keeps {@link #SUBTREE}. */
    private static final PagedTreeWalk DRAIN = SUBTREE.page(BoundedChildren.DRAIN_PAGE);

    /** A one-name probe: does this pointer key also parent deeper keys? */
    private static final BoundedChildren PROBE = BoundedChildren.bounded().entries(1).page(1).steps(1);

    /** The children of a node that is a pointer and a container, each queued as a subtree root. A truncation would
     *  drop holds, so it is refused. */
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
     * One page of review pointers: at most {@code limit} keys in path order strictly after {@code after}
     * ({@code null} from the top), plus the deeper holds of any pointer-and-container node in the page, so the next
     * cursor is always a key the root walk resumes from.
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

    /** The consumer's early exit: an {@link IOException}, the cancellation hook {@link PagedTreeWalk} documents, caught
     *  in {@link #descend}. Stackless and shared, being control flow. */
    private static final class Stop extends IOException {

        private static final long serialVersionUID = 1L;

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    private static final Stop STOP = new Stop();
}
