package build.jenesis.repository.store;

import module java.base;

/**
 * The immediate child names under a prefix, pulled one at a time over the store's ordered pages - the drain a pass
 * runs over a level it must see whole without ever holding whole. {@link ArtifactStore#page} is the primitive: a page
 * of names strictly after a cursor, natively bounded on every shipped backend; this walks page after page at the
 * {@linkplain ArtifactStore#DRAIN_PAGE drain width}, hands out one name at a time, and is exhausted when a page comes
 * back short. What a caller keeps is one page of names, whatever the level holds.
 *
 * <p>Three passes carried this iterator each ({@code Paged} in the VEX store, {@code reapable} in the staging store,
 * a push loop in the torn-write repair), each with the same ten-thousand and the same paragraph explaining it, while
 * five other drains still listed their level whole - the lease reaper, the outbox's parked pruning, the search
 * index's generations and segments, and the quota's recompute at a thousand a page, which is the shape the memory
 * canaries measured as a pass that never finishes. One iterator, one width, and {@link #select} for the drains that
 * keep some names and rename others.
 *
 * <p>A name is a snapshot of a page: a peer deleting behind the cursor moves nothing under it, and a name deleted
 * between the page and its use reads as absent where the caller looks it up, which every drain here already handles.
 */
@FunctionalInterface
public interface Names {

    /** The next name in the store's order, or {@code null} once the level is exhausted. */
    String next() throws IOException;

    /** The children of {@code prefix}, paged at the drain width. */
    static Names over(ArtifactStore store, String prefix) {
        return over(store, prefix, ArtifactStore.DRAIN_PAGE);
    }

    /** The children of {@code prefix}, paged {@code page} at a time - for a test, or a level known to be small. */
    static Names over(ArtifactStore store, String prefix, int page) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(prefix, "prefix");
        if (page <= 0) {
            throw new IllegalArgumentException("a page holds at least one name: " + page);
        }
        return new Names() {
            private final ArrayDeque<String> names = new ArrayDeque<>();
            private String after = "";
            private boolean exhausted;

            @Override
            public String next() throws IOException {
                while (names.isEmpty() && !exhausted) {
                    List<String> fetched = new ArrayList<>();
                    store.page(prefix, after, page, fetched::add);
                    if (fetched.size() < page) {
                        exhausted = true;
                    }
                    if (!fetched.isEmpty()) {
                        after = fetched.getLast();
                        names.addAll(fetched);
                    }
                }
                return names.poll();
            }
        };
    }

    /** What {@link #select} does with one name: the name to hand out, or empty to skip it. */
    @FunctionalInterface
    interface Selection {

        Optional<String> apply(String name) throws IOException;
    }

    /** These names, each mapped through {@code selection} and skipped where it answers empty - the drain that reads
     *  only the rows of one suffix, or the ids whose marker a store read says are still open. */
    default Names select(Selection selection) {
        Objects.requireNonNull(selection, "selection");
        return () -> {
            for (String name = next(); name != null; name = next()) {
                Optional<String> selected = selection.apply(name);
                if (selected.isPresent()) {
                    return selected.get();
                }
            }
            return null;
        };
    }

    /** The names of {@code first}, then of every further iterator in turn - two levels drained as one. */
    static Names concat(Names first, Names... rest) {
        Objects.requireNonNull(first, "first");
        return new Names() {
            private int part;

            @Override
            public String next() throws IOException {
                while (part <= rest.length) {
                    String name = (part == 0 ? first : rest[part - 1]).next();
                    if (name != null) {
                        return name;
                    }
                    part++;
                }
                return null;
            }
        };
    }
}
