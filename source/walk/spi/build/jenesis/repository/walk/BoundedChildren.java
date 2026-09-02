package build.jenesis.repository.walk;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;

/**
 * A bounded, resumable enumeration of <em>one container's</em> immediate child names - the flat sibling of
 * {@link PagedTreeWalk}, and deliberately a different primitive rather than a subtree walk with the depth turned down
 * to one. Half the "unbounded listing" defects in the product are not tree walks at all: a search window scanning a
 * registry's id space, a version list under one coordinate, a revision's file set, a marker space folded into a
 * digest. Those want exactly this - {@link ArtifactStore#page} driven to the end of one container, with a cap and a
 * continuation - and forcing them through a tree walk would buy an {@link ArtifactStore#exists} probe per name that
 * none of them needs.
 *
 * <p><strong>The caps.</strong> An instance is an immutable set of bounds, taken from {@link #bounded()} and narrowed
 * fluently ({@code BoundedChildren.bounded().entries(take).page(take)}):
 * <ul>
 *   <li>{@link #entries()} - how many names one call may deliver ({@value #ENTRIES} by default). Reaching it ends the
 *       call {@link Traversal.Outcome#TRUNCATED} with a cursor.</li>
 *   <li>{@link #steps()} - how many {@link ArtifactStore#page} round-trips one call may issue ({@value #STEPS} by
 *       default). Exceeding it raises {@link TraversalException.Reason#STEPS}: a caller that filters names downstream
 *       (a search scan whose window is small but whose scan is not) is bounded by this, not by the entry cap.</li>
 *   <li>{@link #page()} - the width of each round-trip ({@value #PAGE} by default), the only buffer the call
 *       holds.</li>
 * </ul>
 *
 * <p><strong>One cap truncates; two throw.</strong> The three bounds above do not fail the same way, and the
 * asymmetry is the same one {@link PagedTreeWalk} carries - stated again here because a caller meets this primitive
 * without necessarily meeting that one. Only {@link #entries()} - the bound on how large <em>one answer</em> may be -
 * ends the call as a value ({@link Traversal.Outcome#TRUNCATED} plus a cursor to resume from). {@link #steps()} and
 * the traversal-free segment screen <b>throw</b> {@link TraversalException} and produce no {@link Traversal.Result}
 * at all: a step budget too small to reach the next name would hand back a cursor that makes no forward progress -
 * a livelock dressed up as paging - and a stored name carrying a separator or a {@code .}/{@code ..} segment must
 * never be composed into a key. Truncating either would drop names while answering in the vocabulary of completeness,
 * so a caller must not catch a {@link TraversalException} into a short list. See {@link Traversal} for the rule.
 *
 * <p><strong>The scope root is a legal prefix.</strong> Unlike {@link PagedTreeWalk}, which is always aimed at a named
 * subtree (enumerating a whole store is the resumable, segmented {@link ArtifactWalk}'s job, not a request's), this
 * primitive accepts {@code ""} - the store scope's own children, where a child's name already is its key. Several
 * enumerations legitimately sit there: a tenant's repositories, a scope's top-level spaces. Pass it deliberately;
 * an accidentally empty prefix variable enumerates the whole scope.
 *
 * <p><strong>Why {@code scan} and not {@code list}.</strong> The name is load-bearing, not taste: the
 * bounded-listing clause binds every {@code .list(} call site in the
 * source tree and demands a boundedness justification for each. Naming this method {@code list} would make every
 * migrated - and therefore now provably bounded - call site look like a fresh offender and force a per-site allowlist
 * grant, drowning the ratchet's signal in the very migration that fixes it. Migrating a hand-rolled loop onto this
 * primitive should <em>remove</em> a census entry, not move it. Do not rename it back; the same applies to
 * {@code children}, {@code versions} and {@code coordinates}, the enumeration tokens the disclosure ratchet watches.
 *
 * <p><strong>Exactly at the boundary.</strong> A short page proves the container is drained, so an entry cap met at
 * the end of a short page still answers {@link Traversal.Outcome#EXHAUSTED}; a cap met at the end of a <em>full</em>
 * page answers {@link Traversal.Outcome#TRUNCATED}, and the continuation may then deliver nothing. The bias is the
 * one {@link Traversal.Result} documents: under-claim completeness, never over-claim it.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> An immutable record of bounds, safe to share and drive concurrently; one {@link #scan}
 *       call owns all its mutable state and calls {@link Names} only on the calling thread.</li>
 *   <li><b>Idempotency / replay.</b> A pure read that commits nothing: re-running a call, or resuming from an older
 *       cursor, is always safe. A consumer with side effects must be idempotent per name, since a crash before the
 *       cursor is committed replays the last page.</li>
 *   <li><b>Absence sentinel.</b> An absent or empty container is not an error - {@link Traversal.Outcome#EXHAUSTED}
 *       with zero delivered. {@code null} is never returned; a {@code null} or empty cursor starts at the
 *       beginning.</li>
 *   <li><b>Selection failure.</b> A malformed prefix raises {@link TraversalException}; a cursor that is not an
 *       immediate child key of that prefix, or a non-positive bound, raises {@link IllegalArgumentException}. Neither
 *       degrades to an empty page.</li>
 *   <li><b>Streaming.</b> Names stream to {@link Names} one at a time; only one {@link #page()}-wide page is buffered,
 *       never the container's whole child set the way {@link ArtifactStore#list} does.</li>
 *   <li><b>Tenant scoping.</b> Confined to the children of {@code prefix}; every name is screened as a traversal-free
 *       segment before it is composed into a key, so a backend returning {@code ..} cannot walk the enumeration out
 *       of its subtree.</li>
 *   <li><b>Error visibility.</b> Nothing is swallowed: store failures, consumer failures and the step and segment
 *       bounds propagate as an {@link IOException}. Only the entry cap - the bound with a continuation - is reported
 *       as a value.</li>
 *   <li><b>Read purity.</b> {@link ArtifactStore#page} reads only; no write, no external fetch.</li>
 *   <li><b>Staleness.</b> A live read, not a snapshot: names written during the call are seen only if they sort after
 *       the current page cursor.</li>
 *   <li><b>Ordering / concurrency.</b> Lexicographic child order, exactly {@link ArtifactStore#page}'s, deterministic
 *       and never self-parallelised.</li>
 *   <li><b>Bounded work / cancellation.</b> The three caps bound every call, and the visible outcome at a bound is
 *       asymmetric by design: {@link Traversal.Outcome#TRUNCATED} plus a cursor for the <em>entry</em> cap, which is a
 *       bound on one answer's size and therefore resumable, and a thrown {@link TraversalException} naming the bound
 *       for <em>steps</em> and a hostile segment, which have no continuation that makes progress. A caller may not
 *       convert the second kind into the first. A caller cancels by throwing from {@link Names#accept}.</li>
 *   <li><b>Durability / delivery.</b> Nothing is committed here; the cursor becomes durable only when the caller
 *       writes it through the store, and must be committed after the page's effects so a crash replays a page rather
 *       than skipping one.</li>
 * </ol>
 */
public record BoundedChildren(int steps, int entries, int page) {

    /** Default {@link #steps()}: enough page round-trips to drain a container of {@link #ENTRIES} names at the default
     *  width, and a hard stop long before a pathological container turns one request into an unbounded scan. */
    public static final int STEPS = 1_000;

    /** Default {@link #entries()}: a bounded, committable unit of names per call. */
    public static final int ENTRIES = 10_000;

    /** Default {@link #page()}: the shared {@link Trees#PAGE} page width. */
    public static final int PAGE = Trees.PAGE;

    public BoundedChildren {
        positive("steps", steps);
        positive("entries", entries);
        positive("page", page);
    }

    /** The default bounds - {@value #STEPS} page round-trips, {@value #ENTRIES} names per call, {@value #PAGE}-wide
     *  pages - narrowed fluently by the {@code steps}/{@code entries}/{@code page} methods. */
    public static BoundedChildren bounded() {
        return new BoundedChildren(STEPS, ENTRIES, PAGE);
    }

    /**
     * Bounds for draining a whole container: no cap on names, no cap on round-trips, and {@code page} the only knob
     * left.
     *
     * <p><b>Why this exists rather than two calls.</b> {@link #STEPS} is documented as "enough page round-trips to
     * drain a container of {@link #ENTRIES} names at the default page size" - it is sized <em>for the default entry
     * cap</em>. So raising {@code entries} without raising {@code steps} does not remove the ceiling, it moves it
     * somewhere nobody wrote down: the walk now stops after {@code steps × page} names, and stopping is a
     * {@link TraversalException.Reason#STEPS} rather than an answer.
     *
     * <p>That is not hypothetical. An OCI image with a million tags served fine on memory - the generator streams -
     * and then failed with a 500, because {@code entries(MAX_VALUE).page(1_000)} left the default thousand steps in
     * place and a thousand pages of a thousand names is exactly a million. The two knobs are coupled and were being
     * set one at a time; naming the intent is what stops that.
     */
    public static BoundedChildren draining(int page) {
        return new BoundedChildren(Integer.MAX_VALUE, Integer.MAX_VALUE, page);
    }

    /**
     * The page a drain should use when it has no reason to prefer another, and it is deliberately far larger than
     * {@link #PAGE}.
     *
     * <p><b>Why a drain wants a big page and a request-time read does not.</b> A page costs one
     * {@link ArtifactStore#page} round-trip, and what a round-trip costs depends entirely on the backend. An
     * object store seeks: it is handed the cursor as {@code startAfter} and answers in time proportional to the
     * page. A filesystem cannot seek a directory, so it scans the whole of it and keeps the smallest {@code page}
     * names past the cursor - bounded in memory, as its javadoc says, but linear in the directory <em>every
     * time</em>. Draining a container of N names therefore costs N/page scans of N entries: quadratic, and only on
     * a filesystem.
     *
     * <p>That is not a small effect at the sizes the canaries reach. Attribution's materialisation walks the
     * inventory in {@value #PAGE}-name pages; at a million versions that is a thousand scans of a million entries.
     * Measured, its convergence went from under 6.4 minutes at 400,000 versions to over 35.3 at 1,000,000 - more
     * than five and a half times the work for two and a half times the versions, against the 6.25x a quadratic
     * predicts.
     *
     * <p>So a drain trades memory it can afford for scans it cannot: {@value #DRAIN_PAGE} names in hand instead of
     * {@value #PAGE} cuts the scans by the same factor. Measured on the OCI tag canary at a million tags, on one
     * warm machine, changing nothing else: <b>834 s at a {@value #PAGE} page, 188 s at {@value #DRAIN_PAGE}</b>.
     *
     * <p><b>A single pass was built and then thrown away, which is the more useful half of the measurement.</b>
     * The obvious next step is for the store to drain in one sweep - read the directory once, spill sorted runs,
     * merge them - and that was implemented and measured at the same million tags: <b>198 s</b>, against 188 for
     * simply paging ten times wider. No better, and a k-way merge and a spool file worse to own. The reason is
     * arithmetic: at a {@value #DRAIN_PAGE} page a million names is only a hundred scans, so the quadratic term has
     * already stopped dominating and the remaining time is the work the walk feeds, not the walk.
     *
     * <p>That is a statement about <em>this</em> size, not about the shape. The term is still quadratic, so it
     * returns: at ten million names a {@value #DRAIN_PAGE} page is a thousand scans of ten million, and a single
     * pass would win by orders of magnitude. The fix to reach for then is a store that drains in one sweep - and
     * it should be reached for when a canary shows the quadratic biting again, not before, which is why the code
     * is not carried in the meantime.
     */
    public static final int DRAIN_PAGE = 10_000;

    /** {@link #draining(int)} at the default {@link #DRAIN_PAGE}. */
    public static BoundedChildren draining() {
        return draining(DRAIN_PAGE);
    }

    /** The same bounds with a different round-trip budget. */
    public BoundedChildren steps(int steps) {
        return new BoundedChildren(steps, entries, page);
    }

    /** The same bounds with a different per-call name cap. */
    public BoundedChildren entries(int entries) {
        return new BoundedChildren(steps, entries, page);
    }

    /** The same bounds with a different page width. */
    public BoundedChildren page(int page) {
        return new BoundedChildren(steps, entries, page);
    }

    /** One delivered child name (not a key - compose it onto the prefix). A throw abandons the enumeration. */
    @FunctionalInterface
    public interface Names {

        /** Called once per immediate child name, in the store's lexicographic child order. */
        void accept(String name) throws IOException;
    }

    /** Enumerate {@code prefix}'s children from the beginning - {@link #scan(ArtifactStore, String, String, Names)}
     *  with no cursor. */
    public Traversal.Result scan(ArtifactStore store, String prefix, Names names) throws IOException {
        return scan(store, prefix, null, names);
    }

    /**
     * Deliver the immediate child names of {@code prefix} to {@code names} in the store's child order, starting
     * strictly after {@code cursor} ({@code null} or empty starts at the beginning), until the container is drained or
     * a cap is reached. The cursor is the full key of the last delivered child - the same "a cursor is a store key"
     * rule {@link PagedTreeWalk} follows - so a caller persists and replays one shape of token whichever primitive it
     * drove. Returns {@link Traversal.Result#exhausted} only when a short page proved the container drained. Only the
     * entry cap ends the call as a result; the step budget and the traversal-free segment screen raise
     * {@link TraversalException} rather than shortening the answer.
     */
    public Traversal.Result scan(ArtifactStore store, String prefix, String cursor, Names names) throws IOException {
        String root = Objects.requireNonNull(prefix, "prefix");
        if (!root.isEmpty()) {
            Traversal.root(root); // the empty prefix is the scope's own root, which has no segments to screen
        }
        String after = "";
        if (cursor != null && !cursor.isEmpty()) {
            if (root.isEmpty()) {
                after = cursor;
            } else if (cursor.startsWith(root + "/")) {
                after = cursor.substring(root.length() + 1);
            } else {
                throw new IllegalArgumentException(
                        "Cursor '" + cursor + "' is not a child key of '" + root + "'");
            }
            if (after.indexOf('/') >= 0) {
                throw new IllegalArgumentException(
                        "Cursor '" + cursor + "' is not an IMMEDIATE child key of '" + root + "'");
            }
        }
        String last = cursor;
        long delivered = 0, rounds = 0;
        while (true) {
            if (++rounds > steps) {
                throw new TraversalException(TraversalException.Reason.STEPS, root,
                        "the enumeration issued more than " + steps + " page round-trips");
            }
            List<String> buffer = new ArrayList<>();
            store.page(root, after, page, buffer::add);
            for (String name : buffer) {
                if (delivered == entries) {
                    // Mid-page: names remain in this very buffer, so the container is provably not drained.
                    return Traversal.Result.truncated(last, delivered, rounds);
                }
                String key = Traversal.key(root, name); // screened as a traversal-free segment before it is a key
                names.accept(name);
                last = key;
                delivered++;
            }
            if (buffer.size() < page) {
                return Traversal.Result.exhausted(delivered, rounds); // a short page proves the container is drained
            }
            after = buffer.getLast();
            if (delivered == entries) {
                // A full page ended exactly on the cap: more may or may not follow, so answer truncated and let the
                // continuation prove it - never claim a complete listing that was not proven complete.
                return Traversal.Result.truncated(last, delivered, rounds);
            }
        }
    }

    private static void positive(String bound, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("The " + bound + " bound must be positive: " + value);
        }
    }
}
