package build.jenesis.repository.walk;

import build.jenesis.repository.store.ArtifactStore;

import module java.base;

/**
 * A bounded, resumable descent of one store subtree - the shared primitive every serving surface that must enumerate
 * "all the keys under this coordinate" drives, instead of hand-rolling the same stack walk, the same page loop and the
 * same forgotten cap once per format. It is {@link Trees#descend} (the one iterative descent, never recursion, so an
 * attacker-shaped key depth cannot overflow a thread stack) with four caps and a continuation bolted on; it is not a
 * second traversal pipeline, and it does not page the store any way other than {@link ArtifactStore#page}.
 *
 * <p><strong>The caps.</strong> An instance is an immutable set of bounds, taken from {@link #bounded()} and narrowed
 * fluently ({@code PagedTreeWalk.bounded().depth(8).entries(500)}):
 * <ul>
 *   <li>{@link #depth()} - how many levels below the root a node may sit ({@value #DEPTH} by default, the
 *       {@link ArtifactStore#MAX_SEGMENTS} write-path ceiling, so the default never fires for a key the store would
 *       accept). A deeper node raises {@link TraversalException.Reason#DEPTH}, enforced by {@link Trees} against the
 *       descent's own stack.</li>
 *   <li>{@link #steps()} - how many nodes the descent may open, one {@link ArtifactStore#exists} probe each
 *       ({@value #STEPS} by default). Exceeding it raises {@link TraversalException.Reason#STEPS}. This is the bound
 *       that survives an attacker-shaped tree of a million empty containers holding no leaf at all: a cap on delivered
 *       entries alone would never fire there.</li>
 *   <li>{@link #entries()} - how many leaves one call may deliver ({@value #ENTRIES} by default). Reaching it ends the
 *       call with {@link Traversal.Outcome#TRUNCATED} and a cursor - the continuation, not a failure. Leaves are
 *       counted as delivered to the consumer, so a caller that filters downstream is bounded by {@link #steps()},
 *       not by this.</li>
 *   <li>{@link #page()} - the sibling-page width the descent buffers per open container ({@value #PAGE} by default),
 *       so one pathologically wide container cannot dominate the heap.</li>
 * </ul>
 *
 * <p><strong>One cap truncates; three throw.</strong> The four bounds above do not fail the same way, and reading them
 * as if they did is the single most common mistake against this API. Only {@link #entries()} - the bound on how large
 * <em>one answer</em> may be - ends the call as a value: {@link Traversal.Outcome#TRUNCATED} plus a cursor, which the
 * caller feeds back to get the rest. {@link #depth()}, {@link #steps()} and the traversal-free segment screen
 * <b>throw</b> {@link TraversalException} and produce no {@link Traversal.Result} at all, because they are bounds on
 * how pathological the key space is and none of them has a safe continuation: no cursor in path order can say "resume
 * below a subtree I refused to enter", a step budget too small to re-establish a resume position would hand back a
 * cursor that makes no forward progress, and a name that is not a traversal-free segment must never become a key.
 * Truncating there would drop keys while answering in the vocabulary of completeness - so a caller must not catch a
 * {@link TraversalException} into a short list. See {@link Traversal} for the rule and {@link TraversalException} for
 * each reason's rationale.
 *
 * <p><strong>Exhausted or truncated.</strong> The call answers a {@link Traversal.Result}. Reaching the entry cap
 * never looks like a complete listing: the outcome is {@link Traversal.Outcome#TRUNCATED} and the cursor is the last
 * delivered leaf key. Feeding that cursor back resumes strictly after it, in the same
 * {@linkplain Trees#order path order} the descent visits in, so no key between two calls is skipped and no key is
 * delivered twice. The resume is a <em>seek</em>, not a re-scan: the cursor's own path is descended directly and every
 * container on it pages from just past the cursor's child, so continuing deep inside a huge subtree costs O(depth)
 * probes, not a replay of everything already seen.
 *
 * <p><strong>Crash-resume.</strong> The walk itself persists nothing - the cursor is a value the caller commits
 * alongside whatever the page produced, through the store like all durable state. Commit the derived writes and the
 * cursor together (or the cursor last) and a crash is harmless: a crash before the cursor lands replays the last page
 * into an idempotent consumer, and a crash after it continues from exactly where the committed page ended. A caller
 * that commits the cursor <em>before</em> the page's effects inverts that and loses the tail - the same discipline
 * {@code ArtifactWalk.KeyVisitor#beforeCheckpoint} states for the whole-store walk.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> An instance is an immutable record of bounds and is safe to share, cache in a static
 *       field, and drive from any number of threads. One {@link #walk} call owns all its mutable state; the
 *       {@link Leaves} consumer is called only on the calling thread.</li>
 *   <li><b>Idempotency / replay.</b> {@link #walk} is a pure read and commits nothing, so re-running a call - after a
 *       crash, from the same cursor, or from an older one - is always safe. Delivery is exactly-once per call and
 *       at-least-once across a crash that lost an uncommitted cursor, so a consumer with side effects must be
 *       idempotent per key.</li>
 *   <li><b>Absence sentinel.</b> An absent or empty subtree is not an error: the result is
 *       {@link Traversal.Outcome#EXHAUSTED} with zero delivered and no cursor. {@code null} is never returned; a
 *       {@code null} or empty cursor argument means "start at the beginning".</li>
 *   <li><b>Selection failure.</b> A malformed root or a cursor that is not a key under the root is a caller error and
 *       fails immediately - {@link TraversalException} naming the offending key for a root that is not a
 *       traversal-free key, {@link IllegalArgumentException} for a cursor aimed outside the root or a non-positive
 *       bound. Neither degrades to an empty result.</li>
 *   <li><b>Streaming.</b> Nothing is materialised: leaves stream to {@link Leaves} one key at a time and at most one
 *       {@link #page()}-wide sibling page per open container is held, so the resident cost is O(depth + page), never
 *       O(subtree). A consumer that accumulates into an unbounded list re-introduces exactly the defect this
 *       primitive removes; accumulate at most {@link #entries()} of them.</li>
 *   <li><b>Tenant scoping.</b> The traversal is confined to {@code root} and its descendants and can never compose a
 *       key outside it, so a caller that hands it a tenant-scoped {@link ArtifactStore} (or a tenant-rooted prefix)
 *       cannot read another tenant's keys. The segment screen is what makes that airtight against a backend returning
 *       a {@code ..} name.</li>
 *   <li><b>Error visibility.</b> Nothing is swallowed. A store failure, a consumer failure and a cap with no safe
 *       continuation all propagate as an {@link IOException}; only the entry cap - the one bound that <em>has</em> a
 *       continuation - is reported as a value, and even then never as a complete listing.</li>
 *   <li><b>Read purity.</b> Store reads only ({@link ArtifactStore#exists}, {@link ArtifactStore#page}); no write, no
 *       external fetch, no cursor persistence of its own.</li>
 *   <li><b>Staleness.</b> A walk is a live read of the store, not a snapshot: a key written during the call is
 *       delivered if it sorts after the descent's current position and is not otherwise. A caller resuming from a
 *       cursor across a long gap therefore sees the subtree as it is now, above the cursor.</li>
 *   <li><b>Ordering / concurrency.</b> Leaves arrive in {@linkplain Trees#order path order}, deterministically, and
 *       one call never parallelises itself. Concurrent calls over disjoint cursors of the same subtree are
 *       independent.</li>
 *   <li><b>Bounded work / cancellation.</b> The four caps above bound every call, and the visible outcome at a bound
 *       is asymmetric by design: {@link Traversal.Outcome#TRUNCATED} plus a cursor for the <em>entry</em> cap, which
 *       is a bound on one answer's size and therefore resumable, and a thrown {@link TraversalException} naming the
 *       bound and the key for <em>depth</em>, <em>steps</em> and a hostile segment, which are bounds on how
 *       pathological the key space is and have no continuation that makes progress. A caller may not convert the
 *       second kind into the first. A caller cancels by throwing from {@link Leaves#accept}, which abandons the
 *       descent immediately.</li>
 *   <li><b>Durability / delivery.</b> The primitive commits nothing; the caller owns the commit point. The cursor is
 *       durable only once the caller has written it through the store, and the crash window is exactly the gap
 *       between committing a page's effects and committing its cursor - a window that costs a replay of one page,
 *       never a skipped one, provided the cursor is committed last.</li>
 * </ol>
 */
public record PagedTreeWalk(int depth, int steps, int entries, int page) {

    /** Default {@link #depth()}: the {@link ArtifactStore#MAX_SEGMENTS} write-path segment ceiling, so a walk accepts
     *  every key the store would accept today and refuses anything deeper. */
    public static final int DEPTH = ArtifactStore.MAX_SEGMENTS;

    /** Default {@link #steps()}: far above any real coordinate subtree, far below the work an attacker-shaped tree of
     *  empty containers could otherwise force out of one request. */
    public static final int STEPS = 100_000;

    /** Default {@link #entries()}: a page large enough that no legitimate coordinate subtree is truncated, small
     *  enough that one call's delivered set stays a bounded, committable unit. */
    public static final int ENTRIES = 10_000;

    /** Default {@link #page()}: the shared {@link Trees#PAGE} sibling-page width. */
    public static final int PAGE = Trees.PAGE;

    public PagedTreeWalk {
        positive("depth", depth);
        positive("steps", steps);
        positive("entries", entries);
        positive("page", page);
    }

    /** The default bounds - {@value #DEPTH} segments deep, {@value #STEPS} nodes, {@value #ENTRIES} leaves per call,
     *  {@value #PAGE}-wide sibling pages - narrowed fluently by the {@code depth}/{@code steps}/{@code entries}/
     *  {@code page} methods. */
    public static PagedTreeWalk bounded() {
        return new PagedTreeWalk(DEPTH, STEPS, ENTRIES, PAGE);
    }

    /** The same bounds with a different depth cap. */
    public PagedTreeWalk depth(int depth) {
        return new PagedTreeWalk(depth, steps, entries, page);
    }

    /** The same bounds with a different step budget. */
    public PagedTreeWalk steps(int steps) {
        return new PagedTreeWalk(depth, steps, entries, page);
    }

    /** The same bounds with a different per-call entry cap. */
    public PagedTreeWalk entries(int entries) {
        return new PagedTreeWalk(depth, steps, entries, page);
    }

    /** The same bounds with a different sibling-page width. */
    public PagedTreeWalk page(int page) {
        return new PagedTreeWalk(depth, steps, entries, page);
    }

    /** One delivered leaf key. A throw abandons the descent immediately, so a consumer that cannot accept a key stops
     *  the traversal rather than letting it run on unobserved. */
    @FunctionalInterface
    public interface Leaves {

        /** Called once per in-range stored leaf, in {@linkplain Trees#order path order}. */
        void accept(String key) throws IOException;
    }

    /** Walk {@code root} from the beginning - {@link #walk(ArtifactStore, String, String, Leaves)} with no cursor. */
    public Traversal.Result walk(ArtifactStore store, String root, Leaves leaves) throws IOException {
        return walk(store, root, null, leaves);
    }

    /**
     * Deliver the stored leaves under {@code root} to {@code leaves} in {@linkplain Trees#order path order}, starting
     * strictly after {@code cursor} ({@code null} or empty starts at the beginning), until the subtree is exhausted or
     * a cap is reached. Returns {@link Traversal.Result#exhausted} only when there is provably nothing more;
     * {@link Traversal.Result#truncated} otherwise, carrying the cursor to pass back to continue. Depth, step and
     * hostile-segment bounds raise {@link TraversalException} rather than shortening the answer.
     */
    public Traversal.Result walk(ArtifactStore store, String root, String cursor, Leaves leaves) throws IOException {
        String prefix = Traversal.root(root);
        String resume = cursor == null || cursor.isEmpty() ? null : cursor;
        if (resume != null && !resume.equals(prefix) && !resume.startsWith(prefix + "/")) {
            throw new IllegalArgumentException(
                    "Cursor '" + resume + "' is not a key under the traversal root '" + prefix + "'");
        }
        Bounded bounded = new Bounded(resume, leaves, steps, entries);
        boolean exhausted = Trees.descend(store, prefix, page, depth, bounded);
        return exhausted
                ? Traversal.Result.exhausted(bounded.delivered, bounded.opened)
                : Traversal.Result.truncated(bounded.last, bounded.delivered, bounded.opened);
    }

    private static void positive(String bound, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("The " + bound + " bound must be positive: " + value);
        }
    }

    /** The {@link Trees.Visitor} that turns the unbounded descent into the bounded one: it charges the step budget on
     *  every node the descent opens, filters the resume cursor out of the emitted set, and stops the descent the
     *  moment the entry cap is met - so a cap costs no further store round-trip. Depth and the traversal-free segment
     *  screen are enforced by {@link Trees} itself, at the descent's own stack and composition point. */
    private static final class Bounded implements Trees.Visitor {

        private final String cursor;
        private final Leaves leaves;
        private final int steps;
        private final int entries;

        private String last;
        private long delivered;
        private long opened;
        private boolean stopped;

        private Bounded(String cursor, Leaves leaves, int steps, int entries) {
            this.cursor = cursor;
            this.leaves = leaves;
            this.steps = steps;
            this.entries = entries;
        }

        @Override
        public void visit(String key) throws IOException {
            leaves.accept(key);
            last = key;
            delivered++;
            if (delivered >= entries) {
                stopped = true; // the entry cap: a continuation, reported as TRUNCATED with `last` as the cursor
            }
        }

        @Override
        public boolean emits(String key) throws IOException {
            open(key);
            // Path order is the visit order, so "strictly after the cursor" is exactly "not yet delivered": the seek
            // skips whole earlier subtrees, and this screens the cursor's own path, which the seek descends directly.
            return cursor == null || Trees.order(key, cursor) > 0;
        }

        @Override
        public boolean enters(String prefix) throws IOException {
            open(prefix);
            return true;
        }

        @Override
        public String seek() {
            return cursor;
        }

        @Override
        public boolean proceeds() {
            return !stopped;
        }

        /** Charge one opened node - called exactly once per {@link ArtifactStore#exists} probe the descent makes,
         *  which is why the step budget bounds the traversal's store work rather than only its output. */
        private void open(String key) throws TraversalException {
            if (++opened > steps) {
                throw new TraversalException(TraversalException.Reason.STEPS, key,
                        "the descent opened more than " + steps + " nodes");
            }
        }
    }
}
