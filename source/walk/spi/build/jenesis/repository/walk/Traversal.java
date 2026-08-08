package build.jenesis.repository.walk;

import build.jenesis.repository.store.ArtifactStore;

import module java.base;

/**
 * The shared outcome vocabulary of every bounded store traversal - {@link PagedTreeWalk} (a subtree) and
 * {@link BoundedChildren} (one container's children) both answer in these terms, so a caller reads "did I see
 * everything?" the same way whichever primitive it drove, and the traversal-free segment screen both apply.
 *
 * <p><strong>Exhausted or truncated, never "probably complete".</strong> A {@link Result} is either
 * {@link Outcome#EXHAUSTED} - the traversal reached the end of its scope and there is nothing more - or
 * {@link Outcome#TRUNCATED}, which <em>always</em> carries a continuation {@link Result#cursor() cursor}. The record's
 * constructor enforces that equivalence, so an incomplete traversal cannot be represented as a complete one by
 * accident: there is no way to build an exhausted result that carries a cursor, and no way to build a truncated one
 * that does not. The bounds with no safe continuation raise {@link TraversalException} instead.
 *
 * <p><strong>The cursor is a store key.</strong> Both primitives hand back a key under the traversal root, comparable
 * under {@linkplain Trees#order path order}, and both accept it back verbatim to resume. It is the caller's to
 * persist (through the store, per the engineering principles) once the page it accompanies has been committed; a
 * resume from a persisted cursor continues strictly after the last delivered entry, so a committed page is never
 * re-delivered and no entry between two pages is skipped.
 */
public final class Traversal {

    private Traversal() {
    }

    /** Whether a bounded traversal saw its whole scope, or stopped at a cap with more to come. */
    public enum Outcome {
        /** The traversal reached the end of its scope: everything in scope was delivered. */
        EXHAUSTED,
        /** A cap was reached: what was delivered is a prefix of the scope, and the result's cursor resumes it. */
        TRUNCATED
    }

    /**
     * What one bounded traversal call saw: its {@link Outcome}, the continuation {@code cursor} (present exactly when
     * {@link Outcome#TRUNCATED}), how many entries it {@code delivered}, and how many {@code steps} - nodes opened or
     * page round-trips - it spent doing so. The step count is a diagnostic for an operator sizing a budget, not a
     * completeness proof.
     *
     * <p>A cap reached exactly at the end of the scope answers {@link Outcome#TRUNCATED}, and the continuation then
     * returns an {@link Outcome#EXHAUSTED} result that delivered nothing. The bias is deliberate and one-way: a
     * traversal may under-claim completeness and cost one extra empty round, and may never over-claim it.
     */
    public record Result(Outcome outcome, Optional<String> cursor, long delivered, long steps) {

        public Result {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(cursor, "cursor");
            if (cursor.isPresent() != (outcome == Outcome.TRUNCATED)) {
                throw new IllegalArgumentException(
                        "A truncated result carries a continuation cursor and an exhausted one does not: "
                                + outcome + " / " + cursor);
            }
            if (delivered < 0 || steps < 0) {
                throw new IllegalArgumentException("Negative traversal counters: " + delivered + " / " + steps);
            }
        }

        /** The scope was seen whole: nothing more is coming, and there is no cursor to resume from. */
        public static Result exhausted(long delivered, long steps) {
            return new Result(Outcome.EXHAUSTED, Optional.empty(), delivered, steps);
        }

        /** A cap was reached after {@code cursor}: resume the traversal with it to receive the rest. */
        public static Result truncated(String cursor, long delivered, long steps) {
            return new Result(Outcome.TRUNCATED,
                    Optional.of(Objects.requireNonNull(cursor, "cursor")), delivered, steps);
        }

        /** Whether the whole scope was delivered - the only condition under which a caller may present its result as
         *  a complete listing. */
        public boolean exhausted() {
            return outcome == Outcome.EXHAUSTED;
        }

        /** Whether a cap cut this call short, so {@link #cursor()} must be followed to see the rest. */
        public boolean truncated() {
            return outcome == Outcome.TRUNCATED;
        }
    }

    /**
     * Validate a traversal root and return it: a non-empty key whose every {@code '/'}-separated segment is
     * traversal-free, with no leading, trailing or doubled separator. Rejecting the root here means a caller cannot
     * aim a traversal out of the subtree it meant to scope it to - the read-path counterpart of
     * {@link ArtifactStore#key}'s write-path screen. The whole store is deliberately not a legal root: a bounded
     * traversal is always scoped to a named subtree.
     */
    public static String root(String prefix) throws TraversalException {
        if (prefix == null || prefix.isEmpty()) {
            throw new TraversalException(TraversalException.Reason.SEGMENT, String.valueOf(prefix),
                    "a traversal root must be a non-empty key naming the subtree to walk");
        }
        int start = 0;
        for (int index = 0; index <= prefix.length(); index++) {
            if (index == prefix.length() || prefix.charAt(index) == '/') {
                segment(prefix, prefix, start, index);
                start = index + 1;
            }
        }
        return prefix;
    }

    /**
     * Screen one child name a store backend handed back and return the key it composes to - the single point at which
     * a traversal turns an enumerated name into a key, and so the single point the traversal guard belongs at. The
     * definition of "traversal-free" is exactly {@link ArtifactStore#segment}'s, the product's write-path screen: a
     * name that is empty, {@code .}, {@code ..} or carries a path separator ({@code /} or {@code \}) is rejected.
     * A backend returning such a name (a store written around {@link ArtifactStore#key}, a tampered filesystem, a
     * hostile mount) would otherwise let an enumeration walk out of the subtree it was scoped to, or silently invent
     * a level that is not there. An empty {@code parent} is the store scope's own root, where a child's name already
     * <em>is</em> its key.
     */
    public static String key(String parent, String child) throws TraversalException {
        String key = parent.isEmpty() ? child : parent + "/" + child;
        segment(key, child, 0, child.length());
        return key;
    }

    /** Screen {@code name[start, end)} as one traversal-free segment, reporting against {@code key} - the composed key
     *  an operator needs to see, which is not the segment itself. */
    private static void segment(String key, String name, int start, int end) throws TraversalException {
        if (end == start) {
            throw new TraversalException(TraversalException.Reason.SEGMENT, key, "empty path segment");
        }
        if (end - start <= 2) {
            boolean dots = true;
            for (int index = start; index < end; index++) {
                dots &= name.charAt(index) == '.';
            }
            if (dots) {
                throw new TraversalException(TraversalException.Reason.SEGMENT, key,
                        "current- or parent-directory segment '" + name.substring(start, end) + "'");
            }
        }
        for (int index = start; index < end; index++) {
            char character = name.charAt(index);
            if (character == '/' || character == '\\') {
                throw new TraversalException(TraversalException.Reason.SEGMENT, key,
                        "enumerated name carries a path separator: '" + name.substring(start, end) + "'");
            }
        }
    }
}
