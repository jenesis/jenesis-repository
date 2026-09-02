package build.jenesis.repository.walk;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;

/**
 * The reusable iterative deep-walk over an {@link ArtifactStore}'s key layout - the one descent primitive every
 * store-subtree traversal in the product drives, extracted so no format ever hand-rolls a store tree walk again (the
 * recurring shape behind every "someone re-invented a recursive descent" defect: Debian's {@code collectDebs}, the OCI
 * backfill, and the reference walk itself all re-implemented exactly this before their fixes). Its two riders are the
 * unbounded whole-store enumeration ({@code StoreArtifactWalk}, which segments and resumes it across nodes) and the
 * bounded scoped enumeration ({@link PagedTreeWalk}, which caps and checkpoints it for a serving surface); there is no
 * third traversal pipeline. Given a store and a root prefix, {@link #descend} visits every stored leaf under it in
 * <em>path order</em> - the total order a name-sorted depth-first descent produces, where {@code '/'} sorts below every
 * other character, so a subtree ({@code app/...}) is visited wholly before a longer sibling name it prefixes
 * ({@code app.txt}).
 *
 * <p><strong>Iterative depth, paged width, O(depth) memory.</strong> The descent is driven by an explicit stack of
 * in-progress container cursors, never self-recursion, so an arbitrarily deep key (a many-segment Maven groupId, a
 * multi-segment OCI name - depth is client-planted and, absent {@link ArtifactStore#key} capping, uncapped at the
 * routing edge) is walked to completion instead of overflowing the call stack. Each level holds at most one buffered
 * {@link ArtifactStore#page} page ({@value #PAGE} sibling names by default), so the resident memory is the stack of
 * in-progress containers - O(key-path depth) - and a flat millions-entry namespace is paged, never materialised as one
 * list.
 *
 * <p><strong>Traversal-guarded by construction.</strong> The root is screened once and every child name a store
 * backend hands back is screened as a traversal-free segment at the single point it becomes a key
 * ({@link Traversal#key}), so no rider - the reference walk included - can be walked out of the subtree it scoped
 * itself to by a corrupt or hostile listing. The optional depth ceiling is checked against the descent's own stack,
 * so an attacker-planted depth is refused by name rather than silently pruned.
 *
 * <p><strong>Steering.</strong> The plain {@link #descend(ArtifactStore, String, Visitor)} with a bare
 * {@link Visitor} walks the whole subtree and emits every leaf. A {@link Visitor} that overrides {@link Visitor#seek},
 * {@link Visitor#ceiling}, {@link Visitor#enters} and {@link Visitor#emits} confines the descent to a half-open key
 * range and seeks into it - what {@code StoreArtifactWalk}'s range-segmented, resumable walk needs, expressed once
 * here rather than duplicated per consumer. A {@link Visitor} that overrides {@link Visitor#proceeds} ends the descent
 * early - what {@link PagedTreeWalk} stops on when a cap is reached, so a bound costs no further store work.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> {@link #descend} holds no shared state: one call owns one private stack and one
 *       {@link Visitor}. Concurrent descents over the same store are safe; a single {@link Visitor} instance handed to
 *       two concurrent descents is not, and callers must not do it.</li>
 *   <li><b>Idempotency / replay.</b> A descent is a pure read: re-running it over an unchanged store visits the same
 *       keys in the same order. It commits nothing, so replay is always safe.</li>
 *   <li><b>Absence sentinel.</b> An absent or childless root is not an error - the descent visits nothing and returns
 *       {@code true} (exhausted). {@code null} is never returned or accepted for a key.</li>
 *   <li><b>Tenant scoping.</b> The descent only ever composes keys under {@code prefix}: the root is screened and
 *       every enumerated name is screened as a traversal-free segment before it is composed, so a scoped store or a
 *       tenant-rooted prefix confines the descent even against a backend returning a {@code ..} name.</li>
 *   <li><b>Streaming.</b> No caller-visible collection is materialised: leaves are delivered one at a time and only one
 *       sibling page per open container is buffered. A visitor that accumulates into a list re-introduces the
 *       unbounded materialisation this primitive exists to remove.</li>
 *   <li><b>Error visibility.</b> Nothing is swallowed: an {@link IOException} from the store, the visitor, or a bound
 *       check propagates out of {@link #descend} and abandons the descent. A partially delivered descent is never
 *       reported as a complete one - the caller sees the throw.</li>
 *   <li><b>Read purity.</b> The descent performs store reads only ({@link ArtifactStore#exists} per node,
 *       {@link ArtifactStore#page} per sibling page) and never writes or fetches externally.</li>
 *   <li><b>Ordering.</b> Deterministic and total: path order ({@link #order}), which is also the order every cursor,
 *       range bound and resume token is compared under. Two descents over the same store agree exactly.</li>
 *   <li><b>Bounded work / cancellation.</b> This primitive bounds <em>memory</em> (O(depth) frames, one page per
 *       frame) and, on the overload that takes one, <em>depth</em>; it does not bound the number of nodes it will
 *       visit. {@link Visitor#proceeds} is the cancellation hook, and the return value is the visible outcome:
 *       {@code true} iff the subtree was exhausted, {@code false} iff the visitor stopped it early. A caller that
 *       needs step and entry caps and a continuation cursor uses {@link PagedTreeWalk} rather than re-deriving
 *       them.</li>
 * </ol>
 */
public final class Trees {

    private Trees() {
    }

    /** Sibling names fetched per {@link ArtifactStore#page} call by the default {@link #descend} - the enumeration
     *  buffer width, and the only per-level memory the descent holds. */
    public static final int PAGE = 1000;

    /**
     * Steers a {@link #descend} walk: what to do with each emitted leaf, and - for a bounded consumer - which
     * subtrees to enter, which leaves fall in range, where to seek in first, where the sibling scan stops, and whether
     * to carry on at all. The bound methods default to an unbounded full-subtree walk, so a consumer that only cares
     * about the leaves implements {@link #visit} alone. Every bound is compared under {@linkplain Trees#order path
     * order}, the same order the visit sequence follows, so cursors and range edges stay exactly consistent with what
     * is visited.
     */
    public interface Visitor {

        /** A stored leaf key that {@link #emits} accepted, delivered in path order. */
        void visit(String key) throws IOException;

        /**
         * The same leaf, with whatever the container's listing already knew about it - see
         * {@link ArtifactStore.Listed}. The default drops the metadata and calls {@link #visit(String)}, so a visitor
         * that does not care is unaffected; one that would otherwise ask the store for a leaf's size or age overrides
         * this instead and pays no request for it.
         */
        default void visit(ArtifactStore.Listed entry) throws IOException {
            visit(entry.key());
        }

        /** Whether a stored leaf key falls in range and should be {@link #visit visited}; every leaf by default.
         *  Called exactly once per stored leaf the descent opens, so a bounded visitor charges its per-node budget
         *  here (and in {@link #enters}) and throws a named failure when a cap is breached. */
        default boolean emits(String key) throws IOException {
            return true;
        }

        /** Whether any key under {@code prefix/} can still fall in range - a {@code false} prunes the whole subtree
         *  without descending or paging it; every container by default. Called exactly once per container the descent
         *  opens, before that container is paged, so a depth or segment violation is caught before it costs a
         *  listing. */
        default boolean enters(String prefix) throws IOException {
            return true;
        }

        /** The full key to seek to first inside a container that contains it (the resume cursor or range start),
         *  descended ahead of the paged siblings and without the {@link #ceiling} guard; {@code null} to start every
         *  container from its first child. */
        default String seek() {
            return null;
        }

        /** The exclusive upper key bound the sibling scan stops at - sorted siblings at or past it cannot be in range,
         *  so paging ends there; {@code null} for no upper bound (page to the end of each container). */
        default String ceiling() {
            return null;
        }

        /** Whether the descent should carry on, consulted before each node is opened; always {@code true} by default.
         *  A {@code false} ends the descent immediately - no further {@link ArtifactStore#exists} or
         *  {@link ArtifactStore#page} call is made - and makes {@link Trees#descend} return {@code false}, the visible
         *  "stopped early, not exhausted" outcome a cap-bearing consumer turns into a truncation. */
        default boolean proceeds() {
            return true;
        }
    }

    /**
     * Walk every stored key under {@code prefix} in path order, delivering each to {@code visitor} - an iterative
     * depth-first descent (explicit container stack, no recursion) that consumes the store exclusively through
     * {@link ArtifactStore#page}, so an arbitrarily deep key never overflows the stack and a flat huge namespace is
     * paged rather than buffered. A key where {@link ArtifactStore#exists} is a leaf; a name with children is a
     * container descended before its later siblings (pre-order). When {@code visitor} overrides the bound methods the
     * descent is confined to that half-open range and seeks into it; a bare visitor walks the whole subtree. Returns
     * {@code true} iff the subtree was exhausted and {@code false} iff {@link Visitor#proceeds} stopped it early.
     */
    public static boolean descend(ArtifactStore store, String prefix, Visitor visitor) throws IOException {
        return descend(store, prefix, PAGE, Integer.MAX_VALUE, visitor);
    }

    /**
     * {@link #descend(ArtifactStore, String, Visitor)} with an explicit sibling-page width and depth ceiling - the two
     * knobs a bounded consumer narrows. {@code page} is the per-level enumeration buffer, and so the only knob that
     * trades store round-trips against resident memory. {@code depth} is how many levels below {@code prefix} the
     * descent may open a node at; a deeper node raises {@link TraversalException.Reason#DEPTH} naming that key rather
     * than being skipped, because skipping a subtree drops every key under it and no path-ordered cursor can express
     * a resume beneath one. The ceiling is checked against the descent's own stack, so it costs nothing per node.
     */
    public static boolean descend(ArtifactStore store, String prefix, int page, int depth, Visitor visitor)
            throws IOException {
        if (page <= 0) {
            throw new IllegalArgumentException("Page width must be positive: " + page);
        }
        if (depth <= 0) {
            throw new IllegalArgumentException("Depth ceiling must be positive: " + depth);
        }
        return new Descent(store, visitor, page, depth, Traversal.root(prefix)).run();
    }

    /**
     * The walk's total key order - <em>path order</em>, what a name-sorted depth-first descent visits: character by
     * character with {@code '/'} sorting below every other character, a shorter key before any longer one it prefixes.
     * Plain string order would put a subtree {@code app/...} after a sibling leaf {@code app.txt} ({@code '.'} sorts
     * below {@code '/'}) although the descent, ordering siblings by name, visits the {@code app} subtree first;
     * comparing under path order keeps cursors and range bounds exactly consistent with the visit sequence.
     */
    public static int order(String left, String right) {
        int length = Math.min(left.length(), right.length());
        for (int index = 0; index < length; index++) {
            char first = left.charAt(index), second = right.charAt(index);
            if (first != second) {
                if (first == '/') {
                    return -1;
                }
                if (second == '/') {
                    return 1;
                }
                return Character.compare(first, second);
            }
        }
        return Integer.compare(left.length(), right.length());
    }

    /** One in-flight descent: the ordered depth-first traversal driven by an explicit {@link Frame} stack, so the
     *  memory cost is the stack of in-progress containers - O(key-path depth) - and no key depth can overflow the
     *  call stack. */
    private static final class Descent {

        private final ArtifactStore store;
        private final Visitor visitor;
        private final int page;
        private final int depth;
        private final String root;

        private Descent(ArtifactStore store, Visitor visitor, int page, int depth, String root) {
            this.store = store;
            this.visitor = visitor;
            this.page = page;
            this.depth = depth;
            this.root = root;
        }

        private boolean run() throws IOException {
            Frame top = open(ArtifactStore.Listed.of(root));
            if (top == null) {
                return true; // the root was a leaf (emitted if in range) or a non-intersecting subtree
            }
            Deque<Frame> stack = new ArrayDeque<>();
            stack.push(top);
            while (!stack.isEmpty()) {
                if (!visitor.proceeds()) {
                    return false; // the visitor stopped early: not exhausted, and no further store call is made
                }
                ArtifactStore.Listed child = stack.peek().next();
                if (child == null) {
                    stack.pop(); // this container is drained (or reached the upper bound); ascend
                    continue;
                }
                // The child sits stack.size() levels below the root: the descent's own stack IS the depth counter, so
                // the ceiling is enforced before the node costs a probe and without re-measuring the key.
                if (stack.size() > depth) {
                    throw new TraversalException(TraversalException.Reason.DEPTH, child.key(),
                            stack.size() + " levels below the root '" + root + "' exceeds the depth ceiling of "
                                    + depth);
                }
                Frame descended = open(child);
                if (descended != null) {
                    stack.push(descended); // a container to descend into, before its later siblings - pre-order
                }
            }
            return true;
        }

        /** Process one node: a stored key is a leaf ({@link Visitor#visit} it when {@link Visitor#emits in range}) and
         *  yields no frame; a subtree the visitor will not {@link Visitor#enters enter} is pruned and yields no frame;
         *  any other name is a container to descend, returned as a fresh {@link Frame}. */
        /** The bare child name of a listed key - the paging cursor and the traversal screen both work in names. */
        private static String name(String key) {
            int slash = key.lastIndexOf('/');
            return slash < 0 ? key : key.substring(slash + 1);
        }

        private Frame open(ArtifactStore.Listed entry) throws IOException {
            String key = entry.key();
            // A listing that reported a SIZE has already proven this child is a stored object: only a leaf has one,
            // and a container has none of its own. That makes the existence probe below redundant for exactly the
            // children a descent spends most of its time on, and this descent visits every key in a namespace - so
            // the probe it skips is one store round trip per leaf, not one per pass.
            if (entry.size().isPresent() || store.exists(key)) {
                if (visitor.emits(key)) {
                    visitor.visit(entry);
                }
                return null;
            }
            if (!visitor.enters(key)) {
                return null;
            }
            return new Frame(key);
        }

        /** One container's child cursor: its ordered child enumeration, made resumable so the driver holds a stack of
         *  these instead of a call stack. {@link #next} returns the next child key to descend - the seek-path child
         *  first (descended without the {@link Visitor#ceiling} guard, its own {@link Visitor#enters} prune still
         *  applying the bound), then the paged siblings, ending (yielding {@code null}) at the ceiling or when the last
         *  short page drains. */
        private final class Frame {

            private final String key;
            /** The seek-path child name to descend first, or {@code null} when the {@link Visitor#seek} target is not
             *  inside this container. */
            private final String seekChild;
            private boolean seekYielded;
            private List<ArtifactStore.Listed> buffer;
            private int position;

            private Frame(String key) {
                this.key = key;
                String low = visitor.seek();
                if (low != null && low.startsWith(key + "/")) {
                    String rest = low.substring(key.length() + 1);
                    int slash = rest.indexOf('/');
                    this.seekChild = slash < 0 ? rest : rest.substring(0, slash);
                } else {
                    this.seekChild = null;
                }
            }

            /** The next child key to descend, or {@code null} once this container is exhausted. Every name a store
             *  backend hands back is screened as a traversal-free segment here - the one point a name becomes a key -
             *  so no rider of this descent can be walked out of its subtree by a hostile or corrupt listing. */
            private ArtifactStore.Listed next() throws TraversalException {
                if (seekChild != null && !seekYielded) {
                    // The seek-path child, descended first and WITHOUT the ceiling guard; its own enters() prune (in
                    // open) still applies the upper bound. It is named rather than listed, so it carries no metadata
                    // and open() probes it exactly as before.
                    seekYielded = true;
                    return ArtifactStore.Listed.of(Traversal.key(key, seekChild));
                }
                String ceiling = visitor.ceiling();
                while (true) {
                    if (buffer != null && position < buffer.size()) {
                        ArtifactStore.Listed child = buffer.get(position++);
                        String full = Traversal.key(key, name(child.key()));
                        if (ceiling != null && order(full, ceiling) >= 0) {
                            return null; // sorted siblings: nothing at or past the upper bound can be in range
                        }
                        // Re-key through Traversal.key: that is the one point a listed NAME becomes a key, and it is
                        // where a hostile or corrupt listing is screened as a traversal-free segment.
                        return child.size().isPresent()
                                ? new ArtifactStore.Listed(full, child.size(), child.modified())
                                : ArtifactStore.Listed.of(full);
                    }
                    if (buffer != null && buffer.size() < page) {
                        return null; // the last page was short: this container is drained
                    }
                    // First page starts after the seek child (or at the beginning when no seek); each subsequent page
                    // resumes strictly after the previous page's last name - the ordered-paging cursor.
                    String startAfter = buffer == null
                            ? (seekChild != null ? seekChild : "")
                            : name(buffer.getLast().key());
                    List<ArtifactStore.Listed> next = new ArrayList<>();
                    store.pageListed(key, startAfter, page, next::add);
                    buffer = next;
                    position = 0;
                    if (buffer.isEmpty()) {
                        return null;
                    }
                }
            }
        }
    }
}
