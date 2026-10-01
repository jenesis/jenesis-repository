package build.jenesis.repository.cache.storage;

import module java.base;

import build.jenesis.repository.walk.Traversal;

/**
 * The cache SPI's paging mechanics: how a cursor is read, what order a container enumerates in, and how a page becomes
 * a {@link Traversal.Result}, stated once so no backend reads a cursor differently and silently skips or repeats a
 * page.
 *
 * <p><strong>A cursor is a key</strong>, as for the store's traversals: the last delivered thing's key relative to the
 * scope - a project's bare name, a container's {@code <prefix>/<name>}, an entry's {@code <project>/<step>/<inputs>} -
 * handed back verbatim. So it survives a restart, compares, and names its scope: an entry cursor replayed against
 * another project is refused.
 */
public final class Pages {

    private Pages() {
    }

    /** Screen an enumeration's bound. A non-positive limit is refused, since an empty page reads as a drained
     *  container, and in an eviction pass as "nothing to reclaim". */
    public static int limit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("An enumeration's per-call bound must be positive: " + limit);
        }
        return limit;
    }

    /** The key of a container: its name plus the separator, the ordering key of {@code projects} and {@code listDir},
     *  since object stores list by key and a cursor then resumes with a seek. It is not name order ({@code acme-corp/}
     *  precedes {@code acme/}), as the SPI's ordering clause says. */
    public static String container(String name) {
        return name + "/";
    }

    /** Whether the container {@code name} sorts strictly beyond the boundary {@link #child} read from a cursor. An
     *  empty boundary precedes every name and is not compared, since the separator sorts below a leading {@code .}, and
     *  {@code ".users/"} would otherwise fall before the start. */
    public static boolean beyond(String name, String after) {
        return after.isEmpty() || container(name).compareTo(container(after)) > 0;
    }

    /** The immediate child name an enumeration of {@code prefix} resumes after, or {@code ""} to start. At the scope
     *  root a child's name is its key. A cursor that is not a child key of {@code prefix} is refused, so one listing
     *  never resumes from another's cursor. */
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

    /** The entry key an enumeration of {@code project} resumes after, or {@code ""} to start. A cursor naming no entry
     *  of {@code project} is refused: resuming one project's sweep from another's cursor would skip entries and report
     *  the project swept. */
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

    /** Deliver at most {@code limit} of the ordered container {@code names} and report the outcome. The caller collects
     *  one name more than it may deliver, so the answer is exact: a {@code limit + 1}-th name proves truncation, its
     *  absence exhaustion. {@code steps} is what the backend spent, a diagnostic. */
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

    /** The entry counterpart of {@link #names}, keyed by project-relative key so the cursor is the entry's own key. The
     *  map is ordered - a {@link java.util.TreeMap} where the backend sorted, a {@link java.util.LinkedHashMap} where
     *  its listing was ordered - and holds at most {@code limit + 1} records, never a project's whole entry set. */
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

    /** The cursor of a delivered child: its key under {@code prefix}, or its bare name at the scope root. */
    private static String key(String prefix, String name) {
        String parent = normalised(prefix);
        return parent.isEmpty() ? name : parent + "/" + name;
    }

    /** A prefix without its trailing separator, so {@code ".users"} and {@code ".users/"} compose one cursor. */
    private static String normalised(String prefix) {
        return prefix == null || prefix.isEmpty() || !prefix.endsWith("/")
                ? (prefix == null ? "" : prefix)
                : prefix.substring(0, prefix.length() - 1);
    }
}
