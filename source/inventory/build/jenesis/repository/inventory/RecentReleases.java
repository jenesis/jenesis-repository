package build.jenesis.repository.inventory;

import module java.base;

import build.jenesis.repository.cleanup.Release;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.RecentIndex;

/**
 * The newest-first index of a repository's releases: one small object per published coordinate version, keyed by the
 * publish instant inverted so that a plain ordered page of the namespace is the most recent releases first. It
 * exists so that a screen asking "what was published lately" reads one bounded page instead of walking every
 * publish fact in the repository to keep the newest two hundred, which is what the repository hub did before and
 * what made it cost a minute over a large store.
 *
 * <p>Written on a first publish ({@link InventoryRecording}), removed with the release ({@link InventoryEviction}),
 * backfilled for releases recorded before the index existed by the reconcile pass ({@link InventoryReconciler}),
 * which walks the publish facts in the background anyway. A row whose release has since been withheld or removed
 * without the index hearing of it is filtered at read time by the caller's disclosure check, which is why a page
 * is asked for a few more than it renders.
 */
final class RecentReleases {

    static final String ROOT = "recent";

    private RecentReleases() {
    }

    /** The shared newest-first index this is an encoder over: the inverted-instant keying, the create-only write and
     *  the bounded page live there, and what stays here is what a release row means. */
    private static RecentIndex index(ArtifactStore store) {
        return new RecentIndex(store, ROOT);
    }

    /** What separates two releases recorded in the same millisecond. */
    private static String identity(String ecosystem, String coordinate, String version) {
        return ecosystem + "\0" + coordinate + "\0" + version;
    }

    /** One row of the index: the release triple and when it was published. */
    record Entry(String ecosystem, String coordinate, String version, Instant published) {
    }

    /** A bounded page of the index, newest first, and the key to continue from - {@code null} when exhausted. */
    record Page(List<Entry> entries, String next) {
    }

    /** Write the row as a create-only versioned write, the same small-object idiom as the publish sidecars: a row
     *  already present (the same release recorded twice) is left as it is. */
    static void record(ArtifactStore store, String ecosystem, String coordinate, String version, Instant published)
            throws IOException {
        index(store).record(published, identity(ecosystem, coordinate, version),
                serialize(ecosystem, coordinate, version, published));
    }

    /** Write the row unless it is already there - the reconcile pass's idempotent backfill, a presence probe of
     *  the small key before the write so a settled row costs a read and no write per pass. */
    static void ensure(ArtifactStore store, String ecosystem, String coordinate, String version, Instant published)
            throws IOException {
        index(store).ensure(published, identity(ecosystem, coordinate, version),
                serialize(ecosystem, coordinate, version, published));
    }

    static void forget(ArtifactStore store, Release release) throws IOException {
        if (release.published() != null) {
            index(store).forget(release.published(),
                    identity(release.ecosystem(), release.coordinate(), release.version()));
        }
    }

    /** The page of at most {@code limit} rows after {@code after} (the bare key name of the previous page's last row,
     *  or {@code null} from the top), newest first. */
    static Page page(ArtifactStore store, String after, int limit) throws IOException {
        RecentIndex.Page page = index(store).page(after, limit);
        List<Entry> entries = new ArrayList<>(page.rows().size());
        for (RecentIndex.Row row : page.rows()) {
            parse(row.content()).ifPresent(entries::add);
        }
        return new Page(entries, page.next());
    }

    private static byte[] serialize(String ecosystem, String coordinate, String version, Instant published) {
        return (ecosystem + "\n" + coordinate + "\n" + version + "\n" + published + "\n")
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
