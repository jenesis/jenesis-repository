package build.jenesis.repository.inventory;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.RecentIndex;

/**
 * A newest-first index of a repository's holdings: one small object per coordinate version, keyed by the instant it
 * arrived inverted, so that a plain ordered page of the namespace is the most recent first. It exists so that a
 * screen asking "what arrived lately" reads one bounded page instead of walking every version document to keep the
 * newest two hundred, which is what the repository hub did before and what made it cost a minute over a large store.
 *
 * <p>There are two, one per kind of holding, each a namespace of its own so that neither pays for the other's rows:
 * {@link #RELEASES}, keyed by the publish instant - written on a first publish ({@link InventoryRecording}), removed
 * with the release ({@link InventoryEviction}) - and {@link #CACHED}, keyed by the instant a pull-through first cached
 * the copy. The reconcile pass backfills both from the version documents it reads in the background anyway
 * ({@link InventoryReconciler}). A row whose holding has since been withheld, removed or turned into the other kind
 * without the index hearing of it is filtered at read time by the caller, which is why a page is asked for a few more
 * than it renders.
 */
final class NewestFirst {

    /** The releases this repository published, by publish instant. */
    static final NewestFirst RELEASES = new NewestFirst("recent");

    /** The copies this repository holds from its upstreams, by the instant each was first cached. */
    static final NewestFirst CACHED = new NewestFirst("cached");

    /** The namespace the rows live under; the storage manifest declares it through this constant. */
    final String root;

    private NewestFirst(String root) {
        this.root = root;
    }

    /** The shared newest-first index this is an encoder over: the inverted-instant keying, the create-only write and
     *  the bounded page live there, and what stays here is what a holding row means. */
    private RecentIndex index(ArtifactStore store) {
        return new RecentIndex(store, root);
    }

    /** What separates two holdings recorded in the same millisecond. */
    private static String identity(String ecosystem, String coordinate, String version) {
        return ecosystem + "\0" + coordinate + "\0" + version;
    }

    /** One row of the index: the version triple and the instant it is keyed by. */
    record Entry(String ecosystem, String coordinate, String version, Instant at) {
    }

    /** A bounded page of the index, newest first, and the key to continue from - {@code null} when exhausted. */
    record Page(List<Entry> entries, String next) {
    }

    /** Write the row as a create-only versioned write, the same small-object idiom as the publish sidecars: a row
     *  already present (the same holding recorded twice) is left as it is. */
    void record(ArtifactStore store, String ecosystem, String coordinate, String version, Instant at)
            throws IOException {
        index(store).record(at, identity(ecosystem, coordinate, version), serialize(ecosystem, coordinate, version, at));
    }

    /** Write the row unless it is already there - the reconcile pass's idempotent backfill, a presence probe of
     *  the small key before the write so a settled row costs a read and no write per pass. */
    void ensure(ArtifactStore store, String ecosystem, String coordinate, String version, Instant at)
            throws IOException {
        index(store).ensure(at, identity(ecosystem, coordinate, version), serialize(ecosystem, coordinate, version, at));
    }

    /** Drop the row of a holding, which needs the instant it was keyed by; a {@code null} instant names no row. */
    void forget(ArtifactStore store, String ecosystem, String coordinate, String version, Instant at)
            throws IOException {
        if (at != null) {
            index(store).forget(at, identity(ecosystem, coordinate, version));
        }
    }

    /** The page of at most {@code limit} rows after {@code after} (the bare key name of the previous page's last row,
     *  or {@code null} from the top), newest first. */
    Page page(ArtifactStore store, String after, int limit) throws IOException {
        RecentIndex.Page page = index(store).page(after, limit);
        List<Entry> entries = new ArrayList<>(page.rows().size());
        for (RecentIndex.Row row : page.rows()) {
            parse(row.content()).ifPresent(entries::add);
        }
        return new Page(entries, page.next());
    }

    private static byte[] serialize(String ecosystem, String coordinate, String version, Instant at) {
        return (ecosystem + "\n" + coordinate + "\n" + version + "\n" + at + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static Optional<Entry> parse(byte[] content) {
        String[] lines = new String(content, StandardCharsets.UTF_8).split("\n");
        if (lines.length < 4) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Entry(lines[0], lines[1], lines[2], Instant.parse(lines[3].trim())));
        } catch (DateTimeParseException unreadable) {
            return Optional.empty();
        }
    }
}
