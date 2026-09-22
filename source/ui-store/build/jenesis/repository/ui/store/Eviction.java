package build.jenesis.repository.ui.store;

import module java.base;
import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.cache.storage.CacheStorage.Stored;
import build.jenesis.repository.walk.Traversal;

/**
 * The cache server's eviction policy, run by the ui through the {@link CacheStorage} SPI so it works
 * against whichever backend is configured (filesystem, S3, Azure Blob, GCS) - the same recency-ordered
 * sweeps the server applies, expressed over enumerated {@link Stored} entries. A vanished entry is
 * simply a cache miss for the running server, so these sweeps are race-safe.
 *
 * <p><strong>Every sweep here streams; none of them holds a project.</strong> The SPI's enumerations are paged and
 * resumable, and this class is where that matters most: a size cap used to enumerate a project into one list and sort
 * it, which is an allocation proportional to whatever a build had cached, on the exact code path an operator reaches
 * when the volume is already tight. Each sweep now drives {@link CacheStorage#entries} page by page to exhaustion -
 * the remainder is always followed, never dropped, because a half-swept project would report a total the console
 * shows as fact and would leave a cap unenforced - and the only thing that outlives one page is a bounded
 * {@link #BATCH}-wide selection of what to delete.
 */
public final class Eviction {

    private Eviction() {
    }

    public record Result(long entriesDeleted, long bytesFreed) {
    }

    public record Stats(long entryCount, long totalBytes) {
    }

    /** How many of the coldest (or warmest) entries one selection pass picks before it deletes and re-scans - the
     *  bound on every sweep's in-heap footprint, so a low-disk node reclaims in batches rather than sorting a store. */
    private static final int BATCH = 1024;

    /**
     * Drive one project's paged entry enumeration to exhaustion, handing every entry to {@code action}.
     *
     * <p>The remainder is followed rather than reported, and that is the deliberate choice here: a sweep's answer is a
     * total or a deletion set, and a <em>prefix</em> of either is worse than useless - a truncated total is a number
     * the console prints as the project's size, and a truncated ttl pass reports the project expired while leaving the
     * oldest entries in place. What paging buys the sweep is not a short answer, it is a bounded working set: the
     * enumeration holds one page, and nothing accumulates across pages except the counters and the bounded selection.
     *
     * <p>Deleting from inside the sweep (the ttl and clear passes do) is safe because a cursor is a key, not an index:
     * it names the last delivered entry, and every entry deleted is one already behind it, so a resume continues from
     * a boundary whose disappearance changes nothing about what sorts after it.
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
     * Delete least- (or most-) recently-used entries until the project total is within {@code limit}.
     *
     * <p>Two bounded passes rather than one unbounded one: the total is counted by streaming, and then each round
     * selects only the {@link #BATCH} coldest (or warmest) entries through a bounded heap, deletes what it needs and
     * re-scans for the next batch. The peak footprint is one batch whatever the project holds - where enumerating the
     * project into a list and sorting it, which is what this did, allocated one record per cached entry.
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
     * Global least-recently-used sweep across all projects until the free-space target is met. A low-disk node must
     * never build an in-heap list of the WHOLE store's entries and sort it (an OOM exactly when it is already
     * disk-degraded), so each pass streams the entries a project at a time - through the projects' own paged
     * enumeration and each project's - into one bounded selection, deletes the coldest until the
     * target is met, and re-scans for the next batch; the peak footprint is one batch. The SPI deliberately offers no
     * whole-store sweep to shortcut this with: the union across projects is the caller's to compose, exactly here.
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

    /** The up-to-{@code k} coldest (least-recently-used) entries {@code entries} yields, chosen in one streaming pass
     *  through a bounded max-heap that never retains more than {@code k} - so a free-space reclaim picks what to drop
     *  without ever materialising or sorting the whole store's entry set in heap. Returned coldest-first. */
    public static List<Stored> coldest(Iterable<Stored> entries, int k) {
        Selection selection = new Selection(k, true);
        entries.forEach(selection);
        return selection.selected();
    }

    /**
     * The bounded selection every sweep here drives: the {@code k} entries that sort first under the eviction order -
     * coldest for a least-recently-used policy, warmest for the most-recently-used one - accumulated in one streaming
     * pass through a heap that never holds more than {@code k}.
     *
     * <p>It is a {@link Consumer} rather than a function over a collection precisely so it can be handed straight to
     * the SPI's paged enumeration: the entries arrive one at a time, page by page, and are never anywhere else at
     * once. Selecting across several projects is then just driving one selection through several enumerations, which
     * is what the global reclaim does.
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
