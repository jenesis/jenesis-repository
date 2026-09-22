package build.jenesis.repository.ui.store;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.server.kernel.RepositoryRequests;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.cleanup.CleanupPlan;
import build.jenesis.repository.cleanup.RetentionPolicy;
import build.jenesis.repository.cleanup.RetentionProvider;
import build.jenesis.repository.cleanup.RetentionSweeper;
import build.jenesis.repository.cleanup.StoredReport;
import build.jenesis.repository.gc.GarbageCollector;
import build.jenesis.repository.gc.GarbageCollectorProvider;
import build.jenesis.repository.gc.GcPlan;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.staging.Staging;
import build.jenesis.repository.staging.StagingProvider;
import build.jenesis.repository.store.ArtifactStore;
import io.micrometer.observation.ObservationRegistry;

/**
 * The console's per-repository lifecycle write surface, scoped to the signed-in tenant: staging promote/drop, the
 * retention policy and its pins, the cleanup preview and run (with garbage collection), the retirement of an
 * absent format's records.
 */
public class RepositoryLifecycle extends TenantScope {

    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoryLifecycle.class);

    public RepositoryLifecycle(ArtifactStore repositoryStore, CurrentTenant current, ObservationRegistry observations,
                               AuditTrail audit, ConsoleActor actor) {
        super(repositoryStore, current, observations, audit, actor);
    }

    /** Whether a staging module is installed on this deployment - the console hides the staging surface without one. */
    public boolean stagingAvailable() {
        return StagingProvider.resolve(_ -> null).isPresent();
    }

    /** The first {@code limit} staging repositories and whether more exist, each with its staged-path count capped
     *  at {@link #STAGED_COUNT_CAP} - the hub panel's window, never the whole history. */
    public StagingWindow stagingWindow(String repository, int limit) throws IOException {
        Optional<Staging> staging = stagingFor(repository);
        if (staging.isEmpty()) {
            return new StagingWindow(List.of(), false);
        }
        Staging.Window window = staging.get().ids(limit);
        List<StagingView> views = new ArrayList<>();
        for (String id : window.ids()) {
            views.add(new StagingView(id, staging.get().state(id).name(),
                    staging.get().stagedAtMost(id, STAGED_COUNT_CAP)));
        }
        return new StagingWindow(List.copyOf(views), window.more());
    }

    /** How far a staging repository's paths are counted for the panel; a count at the cap reads as "cap+". */
    public static final int STAGED_COUNT_CAP = 1000;

    public record StagingWindow(List<StagingView> views, boolean more) {
    }

    public void promote(String repository, String id) throws IOException {
        // Matches the /api StagingController's staging.promote event (action and repo/id target) so console and API
        // staging promotions audit identically.
        audit("staging.promote", repository + "/" + id);
        observe("promote", repository, _ -> {
            staged(repository).promote(id);
            return null;
        });
    }

    public void drop(String repository, String id) throws IOException {
        audit("staging.drop", repository + "/" + id);
        observe("drop", repository, _ -> {
            staged(repository).drop(id);
            return null;
        });
    }

    private Staging staged(String repository) {
        return stagingFor(repository)
                .orElseThrow(() -> new IllegalStateException("Staging is not installed on this deployment."));
    }

    /** The ecosystems this repository durably records that no installed format can place - what stands between the
     *  repository and its collector, named so the hub can offer the explicit way out. Empty on a healthy deployment. */
    public SortedSet<String> unplaceableEcosystems(String repository) throws IOException {
        return StoreRepositoryInventory.unplaceableEcosystems(scope(repository));
    }

    /** Forget one unplaceable ecosystem's records - the hub's explicit retirement of an absent format's data, the
     *  same primitive and audit event the {@code /repository/<repo>/admin/forget-ecosystem} API verb drives. */
    public boolean forgetEcosystem(String repository, String ecosystem) throws IOException {
        // Off the request thread. The primitive deletes in pages of 500 across five key-space roots until each is
        // empty, so its cost is however much that ecosystem published - unbounded from here, and paid a round trip
        // at a time against the object store. It held the request open for as long as that took.
        //
        // The refusal is deliberately still raised here rather than inside the pass: an ecosystem an installed
        // format still places must be refused to the operator's face, not reported as a failed background job.
        StoreRepositoryInventory inventory = inventory(repository);
        inventory.refuseIfPlaceable(ecosystem);
        audit(AuditActions.REPOSITORY_FORGET_ECOSYSTEM, repository + " " + ecosystem);
        return StoredReport.compute(scope(repository), forgetReport(ecosystem), () -> {
            long removed = inventory.forgetEcosystem(ecosystem);
            return StoredReport.Rows.of(List.of(removed + " record(s) retired; the collector reclaims the "
                    + "ecosystem's unreferenced content on its next pass"));
        });
    }

    /** The stored report for one ecosystem's retirement, so the hub can read back what the pass did. */
    public static String forgetReport(String ecosystem) {
        return "forget-" + ecosystem;
    }

    /** What the last (or running) retirement of this ecosystem did, for the hub to render. */
    public Optional<StoredReport.Report> forgetOutcome(String repository, String ecosystem) throws IOException {
        return StoredReport.read(scope(repository), forgetReport(ecosystem));
    }

    /** A repository's retention policy, or an empty (keep-everything) policy when none is set. */
    public RetentionPolicy retention(String repository) throws IOException {
        return inventory(repository).readRetention().orElse(new RetentionPolicy(0));
    }

    public void setRetention(String repository, RetentionPolicy policy) throws IOException {
        // Mirrors MaintenanceController's repository.retention event, describing the dials the way the /api leg does.
        audit(AuditActions.REPOSITORY_RETENTION, repository + " keepLast=" + policy.keepLast()
                + (policy.maxAge() == null ? "" : " maxAge=" + policy.maxAge())
                + (policy.prereleaseExpiry() == null ? "" : " prereleaseExpiry=" + policy.prereleaseExpiry())
                + (policy.notDownloadedFor() == null ? "" : " notDownloadedFor=" + policy.notDownloadedFor()));
        observe("set-retention", repository, _ -> {
            inventory(repository).writeRetention(policy);
            return null;
        });
    }

    /** The pinned coordinate versions, decoded into their parts so the form never re-parses a joined string. */
    public List<StoreRepositoryInventory.Pin> pins(String repository) {
        return inventory(repository).pinned();
    }

    /** The ecosystems this repository's inventory already names, suggested (not enforced) by the pin form. */
    public SortedSet<String> ecosystems(String repository) throws IOException {
        StoreRepositoryInventory inventory = inventory(repository);
        // The first level of the publish facts names the ecosystems - a handful of names, never a walk of the
        // coordinates beneath them; the pins are a small namespace of their own.
        SortedSet<String> ecosystems = new TreeSet<>(inventory.ecosystems());
        for (StoreRepositoryInventory.Pin pin : inventory.pinned()) {
            ecosystems.add(pin.ecosystem());
        }
        return ecosystems;
    }

    public void pin(String repository, String ecosystem, String coordinate, String version) throws IOException {
        // Matches MaintenanceController's repository.pin event and its coordinate target shape.
        audit(AuditActions.REPOSITORY_PIN, repository + " " + ecosystem + ":" + coordinate + ":" + version);
        observe("pin", repository, _ -> {
            inventory(repository).pin(ecosystem, coordinate, version);
            return null;
        });
    }

    public void unpin(String repository, String ecosystem, String coordinate, String version) throws IOException {
        audit(AuditActions.REPOSITORY_UNPIN, repository + " " + ecosystem + ":" + coordinate + ":" + version);
        observe("unpin", repository, _ -> {
            inventory(repository).unpin(ecosystem, coordinate, version);
            return null;
        });
    }

    /** Whether a retention module is installed on this deployment - the console hides the retention and cleanup
     *  surface without one. */
    public boolean retentionAvailable() {
        return RetentionProvider.resolve(_ -> null).isPresent();
    }

    /** The stored cleanup preview - what retention would have evicted when it was last computed - or empty when
     *  none was computed yet. A preview is a pass over every release, so the screen reads the stored result and
     *  {@linkplain #previewCleanup starts} a fresh one rather than computing on the request. */
    public Optional<StoredReport.Report> plan(String repository) throws IOException {
        return StoredReport.read(scope(repository), PREVIEW_REPORT);
    }

    /** The stored result of the last cleanup that ran from the console, or empty when none ran yet. */
    public Optional<StoredReport.Report> lastCleanup(String repository) throws IOException {
        return StoredReport.read(scope(repository), CLEANUP_REPORT);
    }

    /** Start computing what retention would evict, in the background; answers whether a run was started (a run
     *  already under way is left alone). */
    public boolean previewCleanup(String repository) throws IOException {
        RetentionSweeper sweeper = sweeper();
        StoreRepositoryInventory inventory = inventory(repository);
        return StoredReport.compute(scope(repository), PREVIEW_REPORT, () -> {
            CleanupPlan plan = sweeper.plan(inventory,
                    inventory.readRetention().orElse(new RetentionPolicy(0)), Instant.now());
            return StoredReport.Rows.of(lines(plan));
        });
    }

    private static List<String> lines(CleanupPlan plan) {
        List<String> evicted = new ArrayList<>();
        for (CleanupPlan.Eviction eviction : plan.evictions()) {
            evicted.add(eviction.release().coordinate() + ":" + eviction.release().version() + " - " + eviction.reason());
        }
        return evicted;
    }

    private static final String PREVIEW_REPORT = "cleanup-preview";
    private static final String CLEANUP_REPORT = "cleanup";

    /** Run the repository's retention, then let the discovered garbage collector reclaim now-unreferenced blobs;
     *  the stored report records the blobs reclaimed. With no collector resolved (the module absent, or configured
     *  off) the sweep still evicts but reclaims nothing - the GC SPI's no-op-by-absence default; the console's
     *  cleanup screen says garbage collection is off ({@code CapabilityService}). The collector reads its settings
     *  from the stored deployment configuration, the same document the settings screens edit. */
    /**
     * Start the repository's retention sweep and the garbage collection behind it, in the background; the result -
     * what was evicted and how many blobs were reclaimed - is stored as the last cleanup report the hub shows.
     * Answers whether a sweep was started; one already under way is left alone. The sweep walks every release, which
     * is why the request that asks for it never waits for it.
     */
    public boolean cleanup(String repository) throws IOException {
        RetentionSweeper sweeper = sweeper();
        StoreRepositoryInventory inventory = inventory(repository);
        ArtifactStore store = scope(repository);
        Properties settings = settings();
        boolean started = StoredReport.compute(store, CLEANUP_REPORT, () -> observe("cleanup", repository, observation -> {
            CleanupPlan plan = sweeper.sweep(inventory, inventory.readRetention().orElse(new RetentionPolicy(0)),
                    Instant.now());
            long reclaimed = 0;
            Optional<GarbageCollector> collector = GarbageCollectorProvider.resolve(settings::getProperty);
            if (collector.isPresent()) {
                GcPlan collected = collector.get().collect(store,
                        StoreRepositoryInventory.pointerRoots(store), Instant.now());
                collected.refusal().ifPresent(refusal ->
                        LOGGER.warn("cleanup {}: {}", repository, refusal.detail()));
                reclaimed = collected.collected();
            }
            observation.highCardinalityKeyValue("reclaimed", Long.toString(reclaimed));
            List<String> rows = new ArrayList<>();
            rows.add(reclaimed + " blobs reclaimed");
            rows.addAll(lines(plan));
            return StoredReport.Rows.of(rows);
        }));
        if (started) {
            audit(AuditActions.REPOSITORY_CLEANUP, repository + " (started)");
        }
        return started;
    }

    private RetentionSweeper sweeper() {
        // The retention engine is a discovered plugin; the cleaner provider ignores configuration.
        return RetentionProvider.resolve(_ -> null)
                .orElseThrow(() -> new IllegalStateException("Retention is not installed on this deployment."));
    }

    private Optional<Staging> stagingFor(String repository) {
        // Staging is a discovered plugin; the store-backed provider ignores configuration, so no lookup is needed.
        return StagingProvider.resolve(_ -> null).map(factory -> factory.over(scope(repository)));
    }

    /** A staging repository as the console lists it: its id, lifecycle state, and how many files it holds. */
    public record StagingView(String id, String state, int items) {
    }

}
