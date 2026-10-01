package build.jenesis.repository.ui.store;

import module java.base;
import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.cache.storage.CacheStorage.Stored;
import build.jenesis.repository.walk.Traversal;

/**
 * The cache's eviction policy over the {@link CacheStorage} SPI, so it works on any backend: the server's
 * recency-ordered sweeps over enumerated {@link Stored} entries. A vanished entry is a cache miss, so the sweeps are
 * race-safe.
 *
 * <p>Every sweep streams {@link CacheStorage#entries} page by page to exhaustion, since a partial sweep would report a
 * wrong total or leave a cap unenforced; only a {@link #BATCH}-wide selection outlives a page.
 */
public final class Eviction {

    private Eviction() {
    }

    public record Result(long entriesDeleted, long bytesFreed) {
    }

    public record Stats(long entryCount, long totalBytes) {
    }

    /** How many entries one selection picks before it deletes and re-scans: every sweep's heap bound. */
    private static final int BATCH = 1024;

    /**
     * Drives one project's paged entry enumeration to exhaustion, handing every entry to {@code action}. Deleting inside
     * the sweep is safe: a cursor names the last delivered entry, and every deleted entry is behind it.
     */
    private static void sweep(CacheStorage storage, String project, Consumer<Stored> action) {
        String cursor = null;
        while (true) {
            Traversal.Result result = storage.entries(project, cursor, CacheStorage.PAGE, action);
            if (result.exhausted()) {
                return;
            }
            cursor = result.cursor().orElseThrow();
        }
    }

    /** Drive the scope's paged project enumeration to exhaustion, handing every project name to {@code action}. */
    private static void projects(CacheStorage storage, Consumer<String> action) {
        String cursor = null;
        while (true) {
            Traversal.Result result = storage.projects(cursor, CacheStorage.PAGE, action);
            if (result.exhausted()) {
                return;
            }
            cursor = result.cursor().orElseThrow();
        }
    }

    /** Count and total size of a project's cache entries, for the listing pages. */
    public static Stats stats(CacheStorage storage, String project) {
        long[] counters = new long[2];
        sweep(storage, project, entry -> {
            counters[0]++;
            counters[1] += entry.size();
        });
        return new Stats(counters[0], counters[1]);
    }

    /**
     * Deletes least- (or most-) recently-used entries until the project total is within {@code limit}: the total is
     * streamed, then each round selects a {@link #BATCH} through a bounded heap, deletes and re-scans.
     */
    public static Result enforceSizeCap(CacheStorage storage, String project, long limit, boolean lru) {
        if (limit <= 0) {
            return new Result(0, 0);
        }
        long total = stats(storage, project).totalBytes();
        if (total <= limit) {
            return new Result(0, 0);
        }
        long deleted = 0, freed = 0;
        while (total > limit) {
            Selection selection = new Selection(BATCH, lru);
            sweep(storage, project, selection);
            List<Stored> batch = selection.selected();
            if (batch.isEmpty()) {
                break;
            }
            boolean progressed = false;
            for (Stored entry : batch) {
                if (total <= limit) {
                    break;
                }
                storage.delete(entry);
                total -= entry.size();
                freed += entry.size();
                deleted++;
                progressed = true;
            }
            if (!progressed || batch.size() < BATCH) {
                break;                          // the project is exhausted (or the batch cannot reach the limit)
            }
        }
        return new Result(deleted, freed);
    }

    /** Delete entries not touched within {@code ttl}. */
    public static Result expireTtl(CacheStorage storage, String project, Duration ttl) {
        if (ttl == null) {
            return new Result(0, 0);
        }
        Instant threshold = Instant.now().minus(ttl);
        long[] counters = new long[2];
        sweep(storage, project, entry -> {
            if (entry.recency().isBefore(threshold)) {
                storage.delete(entry);
                counters[0]++;
                counters[1] += entry.size();
            }
        });
        return new Result(counters[0], counters[1]);
    }

    /** Delete every cache entry in the project (the config files are not entries, so they survive). */
    public static Result clearAll(CacheStorage storage, String project) {
        long[] counters = new long[2];
        sweep(storage, project, entry -> {
            storage.delete(entry);
            counters[0]++;
            counters[1] += entry.size();
        });
        return new Result(counters[0], counters[1]);
    }

    /**
     * A least-recently-used sweep across all projects until the free-space target is met: each round streams every
     * project's entries into one bounded selection, deletes the coldest and re-scans, so the footprint is one batch.
     */
    public static Result reclaim(CacheStorage storage, long minFree, int minFreePercent) {
        long deleted = 0, freed = 0;
        while (low(storage, minFree, minFreePercent)) {
            Selection selection = new Selection(BATCH, true);
            projects(storage, project -> sweep(storage, project, selection));
            List<Stored> batch = selection.selected();
            if (batch.isEmpty()) {
                break;
            }
            boolean progressed = false;
            for (Stored entry : batch) {
                if (!low(storage, minFree, minFreePercent)) {
                    break;
                }
                storage.delete(entry);
                deleted++;
                freed += entry.size();
                progressed = true;
            }
            if (!progressed || batch.size() < BATCH) {
                break;                              // the store is exhausted (or the coldest cannot free the target)
            }
        }
        return new Result(deleted, freed);
    }

    /** The up-to-{@code k} coldest entries, chosen in one streaming pass through a bounded heap, coldest first. */
    public static List<Stored> coldest(Iterable<Stored> entries, int k) {
        Selection selection = new Selection(k, true);
        entries.forEach(selection);
        return selection.selected();
    }

    /**
     * The {@code k} entries that sort first under the eviction order, accumulated through a heap of at most {@code k}. A
     * {@link Consumer}, so it takes the paged enumeration directly, across one project or several.
     */
    private static final class Selection implements Consumer<Stored> {

        /** The eviction order: the entries that sort FIRST are the ones to delete. */
        private final Comparator<Stored> order;
        /** The retained candidates, worst-first, so the one to drop when a better arrives is always the head. */
        private final PriorityQueue<Stored> retained;
        private final int k;

        private Selection(int k, boolean coldest) {
            this.k = k;
            this.order = coldest
                    ? Comparator.comparing(Stored::recency)
                    : Comparator.comparing(Stored::recency).reversed();
            this.retained = new PriorityQueue<>(this.order.reversed());
        }

        @Override
        public void accept(Stored entry) {
            if (retained.size() < k) {
                retained.add(entry);
            } else if (order.compare(entry, retained.peek()) < 0) {
                retained.poll();
                retained.add(entry);
            }
        }

        /** What was selected, in deletion order. */
        private List<Stored> selected() {
            List<Stored> selected = new ArrayList<>(retained);
            selected.sort(order);
            return selected;
        }
    }

    /** Whether the backend is below the configured free-space target (object stores report unlimited). */
    public static boolean low(CacheStorage storage, long minFree, int minFreePercent) {
        if (minFree <= 0 && minFreePercent <= 0) {
            return false;
        }
        long usable = storage.usableSpace(), total = storage.totalSpace();
        if (minFree > 0 && usable < minFree) {
            return true;
        }
        return minFreePercent > 0 && total > 0 && usable * 100L < total * (long) minFreePercent;
    }
}
