package build.jenesis.repository.cache.storage;

import module java.base;

import build.jenesis.repository.walk.Traversal;

/**
 * The cache SPI's paging mechanics: how a cursor is read, what order a container enumerates in, and how a page of
 * names becomes a {@link Traversal.Result}. Stated once here rather than in each backend, for the reason the
 * addressability screens moved into {@link Names}: four hand-written copies of "what does this cursor mean" are four
 * chances to disagree, and a backend that reads a cursor one segment differently from its siblings does not fail - it
 * quietly skips or re-delivers a page, which is precisely the silent incompleteness paging exists to remove.
 *
 * <p>The outcome vocabulary is deliberately <em>not</em> defined here. {@link Traversal.Result} is the free core's,
 * shared with every bounded traversal in the product (&sect;2), and it is the type in which "truncated without a
 * continuation cursor" and "exhausted with one" cannot be constructed at all. This class only assembles one.
 *
 * <p><strong>A cursor is a key.</strong> Exactly as it is for the free core's traversals: the cursor an enumeration
 * hands back is the last delivered thing's key relative to the storage scope - the bare name for a project, the child
 * key {@code <prefix>/<name>} for a container, {@code <project>/<step>/<inputs>} for an entry - and a caller hands it
 * back verbatim. Because it is a key and not an opaque token, it survives a restart, is comparable, and names the
 * scope it belongs to: an entry cursor replayed against a different project is refused rather than silently resuming
 * somewhere plausible.
 */
public final class Pages {

    private Pages() {
    }

    /**
     * Screen one enumeration call's bound and return it. A non-positive limit is refused rather than answered with an
     * empty page: an empty page is indistinguishable from a drained container, so a caller that passed a mis-computed
     * {@code 0} would read an off-by-one as "the store is empty" and, in an eviction pass, as "nothing to reclaim".
     */
    public static int limit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("An enumeration's per-call bound must be positive: " + limit);
        }
        return limit;
    }

    /**
     * The key a container of this name has - the name plus the path separator. It is the ordering key for
     * {@code projects} and {@code listDir}, because it is what the object stores list by natively: their listings
     * arrive in key order, so ordering containers this way is what lets a cursor resume with a server-side seek
     * instead of a client-side re-scan of everything before it. The cost is that it is not <em>name</em> order where
     * one name prefixes another past a character below the separator ({@code acme-corp/} precedes {@code acme/}); the
     * SPI's ordering clause says so out loud, and a caller that needs names sorted sorts its page.
     */
    public static String container(String name) {
        return name + "/";
    }

    /**
     * Whether the container {@code name} sorts strictly beyond the boundary {@link #child} read out of a cursor. An
     * empty boundary is before every name and must be tested as such rather than compared: the separator a container
     * key ends in sorts <em>below</em> a leading {@code .} or {@code -}, so comparing {@code ".users/"} against a bare
     * {@code "/"} would place the console's own config tree before the start of the enumeration and drop it from
     * every first page.
     */
    public static boolean beyond(String name, String after) {
        return after.isEmpty() || container(name).compareTo(container(after)) > 0;
    }

    /**
     * The immediate child name an enumeration of {@code prefix}'s containers resumes strictly after, or {@code ""} to
     * start at the beginning. The empty prefix is the storage scope's own root, where a child's name already is its
     * key. A cursor that is not an immediate child key of {@code prefix} is refused: resuming a listing of one
     * container from another's cursor would silently deliver a page of the wrong space.
     */
    public static String child(String prefix, String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return "";
        }
        String parent = normalised(prefix), name = cursor;
        if (!parent.isEmpty()) {
            if (!cursor.startsWith(parent + "/")) {
                throw new IllegalArgumentException("Cursor '" + cursor + "' is not a child key of '" + parent + "'");
            }
            name = cursor.substring(parent.length() + 1);
        }
        if (name.isEmpty() || name.indexOf('/') >= 0) {
            throw new IllegalArgumentException(
                    "Cursor '" + cursor + "' is not an immediate child key of '" + parent + "'");
        }
        return name;
    }

    /**
     * The project-relative entry key an enumeration of {@code project}'s entries resumes strictly after, or {@code ""}
     * to start at the beginning. A cursor that does not name an entry under {@code project} is refused for the reason
     * above, sharpened: an eviction pass resuming one project's sweep from another project's cursor would skip
     * whatever sorts below it and report the project swept.
     */
    public static String entry(String project, String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return "";
        }
        if (project == null || !cursor.startsWith(project + "/") || cursor.length() == project.length() + 1) {
            throw new IllegalArgumentException(
                    "Cursor '" + cursor + "' is not an entry key of project '" + project + "'");
        }
        return cursor.substring(project.length() + 1);
    }

    /**
     * Deliver at most {@code limit} of the already-ordered container {@code names} under {@code prefix} and report the
     * outcome. The caller collects one more name than it may deliver, so the answer here is exact rather than merely
     * safe: a {@code limit + 1}-th name proves the container is not drained and truncates at the {@code limit}-th,
     * while its absence proves it is and answers exhausted - the caller is never sent back for a page that turns out
     * to be empty. {@code steps} is what the backend spent reaching the page, a diagnostic for an operator sizing a
     * bound rather than a completeness claim.
     */
    public static Traversal.Result names(String prefix, List<String> names, int limit, long steps,
                                         Consumer<String> consumer) {
        long delivered = 0;
        String last = null;
        for (String name : names) {
            if (delivered == limit) {
                return Traversal.Result.truncated(key(prefix, last), delivered, steps);
            }
            consumer.accept(name);
            last = name;
            delivered++;
        }
        return Traversal.Result.exhausted(delivered, steps);
    }

    /**
     * The entry counterpart of {@link #names}: deliver at most {@code limit} of one project's already-ordered entries,
     * keyed by their project-relative key so the continuation cursor is the entry's own key. The map is ordered - a
     * {@link java.util.TreeMap} where the backend had to sort, a {@link java.util.LinkedHashMap} where its listing
     * already arrived ordered - and holds at most {@code limit + 1} records, which is the whole point: a project's
     * entry set is the namespace a build inflates, and it is never materialised whole.
     */
    public static Traversal.Result entries(String project, SequencedMap<String, CacheStorage.Stored> page, int limit,
                                           long steps, Consumer<CacheStorage.Stored> consumer) {
        long delivered = 0;
        String last = null;
        for (Map.Entry<String, CacheStorage.Stored> entry : page.entrySet()) {
            if (delivered == limit) {
                return Traversal.Result.truncated(key(project, last), delivered, steps);
            }
            consumer.accept(entry.getValue());
            last = entry.getKey();
            delivered++;
        }
        return Traversal.Result.exhausted(delivered, steps);
    }

    /** The cursor a delivered child composes to: its key under {@code prefix}, or its bare name at the scope root -
     *  the same "a child's name already is its key at the root" rule the free core's traversals follow. */
    private static String key(String prefix, String name) {
        String parent = normalised(prefix);
        return parent.isEmpty() ? name : parent + "/" + name;
    }

    /** A prefix without its trailing separator, so {@code ".users"} and {@code ".users/"} name one container and
     *  compose one cursor rather than two that a resume would then refuse against each other. */
    private static String normalised(String prefix) {
        return prefix == null || prefix.isEmpty() || !prefix.endsWith("/")
                ? (prefix == null ? "" : prefix)
                : prefix.substring(0, prefix.length() - 1);
    }
}
