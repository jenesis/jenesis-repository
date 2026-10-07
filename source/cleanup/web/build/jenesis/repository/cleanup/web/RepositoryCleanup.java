package build.jenesis.repository.cleanup.web;

import module java.base;
import build.jenesis.repository.cleanup.CleanupPlan;
import build.jenesis.repository.cleanup.RetentionPolicy;
import build.jenesis.repository.cleanup.RetentionSweeper;
import build.jenesis.repository.cleanup.StoredReport;
import build.jenesis.repository.gc.GarbageCollector;
import build.jenesis.repository.gc.GarbageCollectorProvider;
import build.jenesis.repository.gc.GcPlan;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.server.Observations;
import build.jenesis.repository.server.kernel.MaintenanceScheduler;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ServableNames;
import io.micrometer.observation.ObservationRegistry;

/**
 * A repository's cleanup - retention's evictions, then the collector's reclaim - and its dry run, as one capability
 * behind the API's {@code /api/repository/cleanup}, the console's cleanup panel and, through the API, the CLI. Both
 * walk every release, so neither runs on a request: a start runs it off the request under a stored report - one for
 * the sweep, one for the preview - and every surface reads that report back, so a sweep started on one surface is the
 * run the others see rather than a second beside it.
 *
 * <p>The sweep takes the scheduled pass's single-writer {@code cleanup} lease when a scheduler is at hand, so an
 * on-demand sweep never runs beside the scheduled one or another node's; a lease another holder has fails the run,
 * saying so, rather than waiting. A composition with no scheduler - a console booted alone - sweeps without one.
 *
 * <p>The eviction rows are screened for served-view parity: retention judges every published release, withheld ones
 * included, and the report is read at {@code repository:read}, so a held member's name becomes {@code <withheld>}
 * while its reason stays. Fail-closed per row: a failing probe hides the name.
 */
public final class RepositoryCleanup {

    /** The stored report the sweep runs under, one per repository. */
    public static final String RUN = "cleanup";

    /** The stored report the dry run runs under, one per repository. */
    public static final String PLAN = "cleanup-preview";

    /** The prefix of the report's first row, which carries the collection leg rather than an eviction. */
    private static final String GC_ROW = "gc\t";

    private final Optional<RetentionSweeper> sweeper;
    private final Supplier<MaintenanceScheduler> maintenance;
    private final ObservationRegistry observations;

    /** The collector providers, discovered once - which are installed cannot change within a JVM; which is selected
     *  is decided per run from the configuration it is handed. */
    private final List<GarbageCollectorProvider> collectors = GarbageCollectorProvider.providers();

    /** @param maintenance resolved at use, answering {@code null} where no scheduler runs, which leaves the sweep
     *                    unleased. */
    public RepositoryCleanup(Optional<RetentionSweeper> sweeper, Supplier<MaintenanceScheduler> maintenance,
                             ObservationRegistry observations) {
        this.sweeper = sweeper;
        this.maintenance = maintenance;
        this.observations = observations;
    }

    /** Whether a retention module is installed; without one there is nothing to sweep. */
    public boolean installed() {
        return sweeper.isPresent();
    }

    /**
     * Start the sweep of {@code store} under {@code policy}, collecting with the collector {@code config} selects;
     * {@code bind} decorates the pass before it is handed to its thread - the seam a caller that resolves its tenant
     * from the request binds it through. Answers whether this call started it rather than finding one running.
     */
    public boolean start(ArtifactStore store, String tenant, String repo, RetentionPolicy policy,
                         UnaryOperator<String> config, UnaryOperator<StoredReport.Pass> bind) throws IOException {
        RetentionSweeper sweeps = sweeper.orElseThrow(RepositoryCleanup::notInstalled);
        return StoredReport.compute(store, RUN, bind.apply(() -> {
            MaintenanceScheduler scheduler = maintenance.get();
            StoredReport.Pass sweep = () -> Observations.observe(observations, "jenrepo.cleanup", repo, tenant,
                    observation -> {
                        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
                        CleanupPlan plan = sweeps.sweep(inventory, policy, Instant.now());
                        // Retention evicts, then the collector reclaims, as the scheduled pass orders them.
                        Optional<GarbageCollector> collector = GarbageCollectorProvider.resolve(collectors, config);
                        GcView gc = collector.isEmpty() ? GcView.OFF : GcView.of(collector.get().collect(store,
                                StoreRepositoryInventory.pointerRoots(store), Instant.now()));
                        observation.lowCardinalityKeyValue("evicted", Integer.toString(plan.evictions().size()));
                        return rows(inventory, plan, gc);
                    });
            if (scheduler == null) {
                return sweep.run();
            }
            // The block-with-return selects the value-returning overload over the scheduler's void one.
            Optional<StoredReport.Rows> swept = scheduler.exclusively(RUN, Instant.now(), () -> {
                return sweep.run();
            });
            return swept.orElseThrow(() -> new IOException("a cleanup sweep is already running on another node, "
                    + "so this one did not start"));
        }));
    }

    /** Start the dry run of {@code store} under {@code policy}: what retention would evict and what the collector
     *  {@code config} selects would reclaim, writing nothing. Answers whether this call started it. */
    public boolean startPlan(ArtifactStore store, RetentionPolicy policy, UnaryOperator<String> config,
                             UnaryOperator<StoredReport.Pass> bind) throws IOException {
        RetentionSweeper sweeps = sweeper.orElseThrow(RepositoryCleanup::notInstalled);
        return StoredReport.compute(store, PLAN, bind.apply(() -> {
            StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
            CleanupPlan plan = sweeps.plan(inventory, policy, Instant.now());
            // GarbageCollector.plan writes nothing; with no collector the view says collection is off.
            Optional<GarbageCollector> collector = GarbageCollectorProvider.resolve(collectors, config);
            GcView gc = collector.isEmpty() ? GcView.OFF : GcView.of(collector.get().plan(store,
                    StoreRepositoryInventory.pointerRoots(store), Instant.now()));
            return rows(inventory, plan, gc);
        }));
    }

    /** What the last sweep - or, with {@code plan}, the last dry run - found, or that none has run, or that one is
     *  running now: one point read. */
    public View read(ArtifactStore store, boolean plan) throws IOException {
        Optional<StoredReport.Report> stored = StoredReport.read(store, plan ? PLAN : RUN);
        if (stored.isEmpty()) {
            return new View(plan, "not-run", null, null, 0, List.of(), GcView.OFF, null);
        }
        StoredReport.Report report = stored.get();
        // A running or failed run keeps the last finished answer readable beneath it.
        Optional<StoredReport.Report> finished = report.lastFinished();
        List<String> rows = finished.map(StoredReport.Report::rows).orElse(List.of());
        GcView gc = GcView.OFF;
        List<String> evicted = new ArrayList<>();
        for (String row : rows) {
            if (row.startsWith(GC_ROW)) {
                gc = GcView.parse(row.substring(GC_ROW.length()));
            } else {
                evicted.add(row);
            }
        }
        boolean failed = report.status() == StoredReport.Status.FAILED;
        return new View(plan, report.running() ? "running" : failed ? "failed" : "done", report.startedAt(),
                finished.map(done -> done.finishedAt()).orElse(null), finished.map(StoredReport.Report::count).orElse(0),
                evicted, gc, failed && report.failure() != null && !report.failure().isBlank() ? report.failure()
                        : null);
    }

    /** The report's rows: the collection leg first, then the screened evictions; the count is the evictions'. */
    private static StoredReport.Rows rows(StoreRepositoryInventory inventory, CleanupPlan plan, GcView gc) {
        List<String> rows = new ArrayList<>();
        rows.add(GC_ROW + gc.encode());
        for (CleanupPlan.Eviction eviction : plan.evictions()) {
            if (rows.size() >= StoredReport.SAMPLE) {
                break;                                      // the report names a sample; the count carries the total
            }
            String display = eviction.release().coordinate() + ":" + eviction.release().version();
            boolean disclosable;
            try {
                disclosable = inventory.disclosableDisplay(display, ServableNames.Policy.HIDE_WITHHELD);
            } catch (IOException e) {
                disclosable = false;
            }
            rows.add((disclosable ? display : "<withheld>") + " - " + eviction.reason());
        }
        return new StoredReport.Rows(plan.evictions().size(), List.copyOf(rows));
    }

    private static IllegalStateException notInstalled() {
        return new IllegalStateException("Retention is not installed on this deployment.");
    }

    /**
     * A sweep's or dry run's state: {@code plan} says which, {@code state} is {@code not-run}, {@code running},
     * {@code done} or {@code failed}; {@code startedAt} is when the latest run began, {@code finishedAt} when the last
     * finished one ended ({@code null} before one has); {@code evictedCount} counts its evictions and {@code evicted}
     * names the first of them, a withheld member as {@code <withheld>}; {@code gc} is its collection leg; and
     * {@code failure} says why the latest run stopped, {@code null} unless it did.
     */
    public record View(boolean plan, String state, Instant startedAt, Instant finishedAt, int evictedCount,
                       List<String> evicted, GcView gc, String failure) {

        /** Whether a run is going now - what a caller watching it polls on. */
        public boolean running() {
            return "running".equals(state);
        }

        /** Whether any run has finished, so the counts say something. */
        public boolean finished() {
            return finishedAt != null;
        }

        /** What a sweep deleted: the collector's reclaim, {@code 0} from a dry run. */
        public long blobsReclaimed() {
            return plan ? 0 : gc.collected();
        }
    }

    /**
     * The collection leg of a sweep or dry run, mirroring {@code GcPlan}: whether a collector is installed, whether the
     * judgment rests on a completed enumeration, what was condemned, spared and collected, and {@code refusal} - why the
     * pass declined to judge anything, empty in the ordinary case.
     *
     * <p>{@code refusal} tells the two "nothing happened" answers apart: a pass another node holds segments of, and a
     * pass refused because an ecosystem's roots cannot be named, both report {@code complete=false} with zero counters;
     * only the second is an action item, naming the module to reinstall.
     */
    public record GcView(boolean installed, boolean complete, long condemned, long spared, long collected,
                         String refusal) {

        /** No collector resolved - the no-op-by-absence default: garbage collection is off, nothing is reclaimed. */
        static final GcView OFF = new GcView(false, false, 0, 0, 0, "");

        static GcView of(GcPlan plan) {
            return new GcView(true, plan.complete(), plan.condemned(), plan.spared(), plan.collected(),
                    plan.refusal().map(Object::toString).orElse(""));
        }

        /** The view as one stored row; the refusal goes last, since it is free text. */
        String encode() {
            return installed + "\t" + complete + "\t" + condemned + "\t" + spared + "\t" + collected + "\t"
                    + refusal.replace('\n', ' ');
        }

        static GcView parse(String row) {
            String[] parts = row.split("\t", 6);
            if (parts.length < 6) {
                return OFF;
            }
            try {
                return new GcView(Boolean.parseBoolean(parts[0]), Boolean.parseBoolean(parts[1]),
                        Long.parseLong(parts[2]), Long.parseLong(parts[3]), Long.parseLong(parts[4]), parts[5]);
            } catch (NumberFormatException _) {
                return OFF;
            }
        }
    }
}
