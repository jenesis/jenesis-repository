package build.jenesis.repository.walk;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;

/**
 * A bounded, resumable enumeration of <em>one container's</em> immediate child names - the flat sibling of
 * {@link PagedTreeWalk}, and deliberately a different primitive rather than a subtree walk with the depth turned down
 * to one. Many enumerations are not tree walks at all: a search window scanning a
 * registry's id space, a version list under one coordinate, a revision's file set, a marker space folded into a
 * digest. Those want exactly this - {@link ArtifactStore#page} driven to the end of one container, with a cap and a
 * continuation - and forcing them through a tree walk would buy an {@link ArtifactStore#exists} probe per name that
 * none of them needs.
 *
 * <p><strong>The caps.</strong> An instance is an immutable set of bounds, taken from {@link #bounded()} and narrowed
 * fluently ({@code BoundedChildren.bounded().entries(take).page(take)}):
 * <ul>
 *   <li>{@link #entries()} - how many names one call may deliver ({@value #ENTRIES} by default). Reaching it ends the
 *       call {@linkplain Traversal.Result#truncated() truncated} with a cursor.</li>
 *   <li>{@link #steps()} - how many {@link ArtifactStore#page} round-trips one call may issue ({@value #STEPS} by
 *       default). Exceeding it raises {@link TraversalException.Reason#STEPS}: a caller that filters names downstream
 *       (a search scan whose window is small but whose scan is not) is bounded by this, not by the entry cap.</li>
 *   <li>{@link #page()} - the width of each round-trip ({@value #PAGE} by default), the only buffer the call
 *       holds.</li>
 * </ul>
 *
 * <p><strong>One cap truncates; two throw.</strong> Only {@link #entries()} - the bound on how large <em>one
 * answer</em> may be - ends the call as a value ({@linkplain Traversal.Result#truncated() truncated} plus a cursor to
 * resume from). {@link #steps()} and the traversal-free segment screen <b>throw</b> {@link TraversalException},
 * because neither has a continuation that makes progress, so a caller must not catch one into a short list.
 * {@link Traversal} states the rule.
 *
 * <p><strong>The scope root is a legal prefix.</strong> Unlike {@link PagedTreeWalk}, which is always aimed at a named
 * subtree (enumerating a whole store is the resumable, segmented {@link ArtifactWalk}'s job, not a request's), this
 * primitive accepts {@code ""} - the store scope's own children, where a child's name already is its key. Several
 * enumerations legitimately sit there: a tenant's repositories, a scope's top-level spaces. Pass it deliberately;
 * an accidentally empty prefix variable enumerates the whole scope.
 *
 * <p><strong>Exactly at the boundary.</strong> A short page proves the container is drained, so an entry cap met at
 * the end of a short page still answers {@linkplain Traversal.Result#exhausted() exhausted}; a cap met at the end of a
 * <em>full</em> page answers {@linkplain Traversal.Result#truncated() truncated}, and the continuation may then deliver
 * nothing. The bias is the one {@link Traversal.Result} documents: under-claim completeness, never over-claim it.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> An immutable record of bounds, safe to share and drive concurrently; one {@link #scan}
 *       call owns all its mutable state and calls {@link Names} only on the calling thread.</li>
 *   <li><b>Idempotency / replay.</b> A pure read that commits nothing: re-running a call, or resuming from an older
 *       cursor, is always safe. A consumer with side effects must be idempotent per name, since a crash before the
 *       cursor is committed replays the last page.</li>
 *   <li><b>Absence sentinel.</b> An absent or empty container is not an error -
 *       {@linkplain Traversal.Result#exhausted() exhausted} with zero delivered. {@code null} is never returned; a
 *       {@code null} or empty cursor starts at the beginning.</li>
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
 *       asymmetric by design: {@linkplain Traversal.Result#truncated() truncated} plus a cursor for the <em>entry</em>
 *       cap, which is a bound on one answer's size and therefore resumable, and a thrown {@link TraversalException}
 *       naming the bound for <em>steps</em> and a hostile segment, which have no continuation that makes progress. A
 *       caller may not convert the second kind into the first. A caller cancels by throwing from
 *       {@link Names#accept}.</li>
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
     * <p>{@link #STEPS} is sized <em>for the default entry cap</em>, so raising {@code entries} without raising
     * {@code steps} does not remove the ceiling, it moves it to {@code steps × page} names - a million at the
     * defaults - where stopping is a {@link TraversalException.Reason#STEPS} rather than an answer. The two knobs are
     * coupled, and this names the intent so they are set together.
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
     * <p>So a drain trades memory it can afford for scans it cannot: {@value #DRAIN_PAGE} names in hand instead of
     * {@value #PAGE} cuts the scans by the same factor, and at a million names a drain is a hundred scans, where the
     * quadratic term no longer dominates the work the walk feeds.
     *
     * <p>The term is still quadratic: at ten million names this page is a thousand scans of ten million, and a store
     * that drains in one sweep (read the directory once, merge sorted runs) would then win by orders of magnitude.
     */
    public static final int DRAIN_PAGE = ArtifactStore.DRAIN_PAGE;

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
