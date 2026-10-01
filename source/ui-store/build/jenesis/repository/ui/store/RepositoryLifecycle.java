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
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.RepositoryDocument;
import build.jenesis.repository.store.RepositoryRemoval;
import io.micrometer.observation.ObservationRegistry;

/**
 * The console's per-repository lifecycle write surface, scoped to the signed-in tenant: staging promote/drop, the
 * retention policy and its pins, the cleanup preview and run (with garbage collection), the retirement of an
 * absent format's records.
 */
public class RepositoryLifecycle extends TenantScope {

    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoryLifecycle.class);

    private final SettingsAdmin settings;

    /** {@code settings} resolves a repository's retention rules, the policy a preview, a cleanup and the scheduled
     *  sweep all judge by. */
    public RepositoryLifecycle(ArtifactStore repositoryStore, CurrentTenant current, ObservationRegistry observations,
                               AuditTrail audit, ConsoleActor actor, SettingsAdmin settings) {
        super(repositoryStore, current, observations, audit, actor);
        this.settings = settings;
    }

    /**
     * Makes a repository of the signed-in tenant hold the type {@code format}, through the one creation every surface
     * makes ({@link RepositoryType#create}): one holding no format is given it, and one whose type the requested one
     * contains ({@code maven} asked to be {@code java}) is moved to it.
     *
     * @return what the creation did; {@link RepositoryType.Creation#CONFLICT} leaves the repository as it was.
     * @throws IllegalArgumentException when the name is not a repository name or the type is not one a repository
     *                                  can hold here.
     */
    public RepositoryType.Creation create(String repository, String format) throws IOException {
        return create(repository, format, "");
    }

    /**
     * {@link #create(String, String)} with a description. A repository being deleted is refused, since its objects are
     * still going.
     *
     * @throws IllegalArgumentException when the name, the type or the description is refused.
     */
    public RepositoryType.Creation create(String repository, String format, String description) throws IOException {
        return create(repository, format, description, null, false);
    }

    /**
     * Creates a new repository with {@code values} as its own settings, as the repository wizard completes. Every value
     * is validated first and a refusal writes nothing; the settings are stored before the document that makes the
     * repository exist ({@link RepositoryType#create(ArtifactStore, String, String, RepositoryType.Configuration)}). An
     * existing repository is answered {@link RepositoryType.Creation#EXISTS}, unchanged.
     *
     * @param operator whether the session is the deployment operator's, who alone sets an operator-only setting.
     * @throws IllegalArgumentException when the name, the type, the description or any value is refused - naming
     *                                  every refused value.
     */
    public RepositoryType.Creation create(String repository, String format, String description,
                                          Map<String, String> values, boolean operator) throws IOException {
        if (!RepositoryType.offerable().contains(format)) {
            throw new IllegalArgumentException("'" + format + "' is not a format this deployment serves.");
        }
        String line = RepositoryDocument.description(description);
        if (RepositoryRemoval.removing(scope(repository))) {
            throw new IllegalArgumentException("Repository '" + repository + "' is still being deleted; create it "
                    + "again once it is gone.");
        }
        if (values != null) {
            SortedMap<String, String> refused = settings.refusals(Setting.Scope.REPOSITORY, values, operator);
            if (!refused.isEmpty()) {
                throw new IllegalArgumentException(String.join(" ", refused.values()));
            }
        }
        String tenant = tenant();
        RepositoryType.Creation creation = RepositoryType.create(scope(repository), format, line, values == null
                ? null : () -> {
                    if (!values.isEmpty()) {
                        settings.saveRepository(tenant, repository, values, operator);
                    }
                });
        if ((creation == RepositoryType.Creation.UNCHANGED || creation == RepositoryType.Creation.RETYPED)
                && !line.isEmpty()) {
            describe(repository, line);
        }
        switch (creation) {
            case CREATED -> audit(AuditActions.REPOSITORY_CREATE, repository);
            case RETYPED -> audit(AuditActions.REPOSITORY_RETYPE, repository + " to " + format);
            default -> {
            }
        }
        return creation;
    }

    /**
     * What refuses a new repository's name, format or description, by field; empty when a creation would be accepted.
     * The wizard asks on leaving its first step, and the creation decides again.
     */
    public Map<String, String> identityRefusals(String repository, String format, String description)
            throws IOException {
        Map<String, String> refused = new LinkedHashMap<>();
        String name = repository == null ? "" : repository.trim();
        if (name.isEmpty()) {
            refused.put("name", "A repository needs a name.");
        } else if (!Scopes.valid(name)) {
            refused.put("name", "A repository name is letters, digits, hyphens and underscores.");
        } else if (RepositoryDocument.exists(scope(name))) {
            refused.put("name", "Repository '" + name + "' exists already.");
        } else if (RepositoryRemoval.removing(scope(name))) {
            refused.put("name", "Repository '" + name + "' is still being deleted; create it again once it is gone.");
        }
        if (!RepositoryType.offerable().contains(format)) {
            refused.put("format", "'" + format + "' is not a format this deployment serves.");
        }
        try {
            RepositoryDocument.description(description);
        } catch (IllegalArgumentException tooLong) {
            refused.put("description", tooLong.getMessage());
        }
        return refused;
    }

    /**
     * Give a repository {@code description}, empty to clear it.
     *
     * @return {@code false} when there is no repository by that name.
     * @throws IllegalArgumentException when the description is longer than a repository takes.
     */
    public boolean describe(String repository, String description) throws IOException {
        boolean described = RepositoryDocument.describe(scope(repository), description);
        if (described) {
            RepositoryDocument.forget(root, tenant(), repository);
            audit(AuditActions.REPOSITORY_DESCRIBE, repository);
        }
        return described;
    }

    /**
     * Deletes a repository: it stops answering at once ({@link RepositoryRemoval#begin}) and its objects are removed on
     * a thread of their own. A deletion a node stopped part way is resumed.
     *
     * @return what beginning the removal found; {@link RepositoryRemoval.Begun#ABSENT} deletes nothing.
     */
    public RepositoryRemoval.Begun delete(String repository) throws IOException {
        ArtifactStore scope = scope(repository);
        RepositoryRemoval.Begun begun = RepositoryRemoval.begin(scope);
        if (begun == RepositoryRemoval.Begun.ABSENT) {
            return begun;
        }
        RepositoryDocument.forget(root, tenant(), repository);
        audit(AuditActions.REPOSITORY_DELETE, repository);
        RepositoryRemoval.purgeInBackground(root.scope(tenant()), repository, tenant() + "/" + repository);
        return begun;
    }

    /** Whether a staging module is installed on this deployment - the console hides the staging surface without one. */
    public boolean stagingAvailable() {
        return StagingProvider.resolve(_ -> null).isPresent();
    }

    /** The first {@code limit} staging repositories and whether more exist, each counted up to
     *  {@link #STAGED_COUNT_CAP}. */
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
        // Recorded as the API's staging promotion is.
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

    /** The ecosystems this repository records that no installed format can place, which hold up its collector. */
    public SortedSet<String> unplaceableEcosystems(String repository) throws IOException {
        return StoreRepositoryInventory.unplaceableEcosystems(scope(repository));
    }

    /** Starts retiring one unplaceable ecosystem's records, as {@code /api/repository/forget-ecosystem} does. */
    public boolean forgetEcosystem(String repository, String ecosystem) throws IOException {
        // The deletion runs off the request, as its cost is whatever the ecosystem published; the refusal of a placeable
        // ecosystem is raised here, to the operator.
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

    /** The retention policy a repository runs under, resolved as the scheduled sweep resolves it. */
    public RetentionPolicy retention(String repository) throws IOException {
        return RetentionPolicy.fromConfig(settings.repositoryConfig(tenant(), repository));
    }

    /** The pinned coordinate versions, decoded into their parts so the form never re-parses a joined string. */
    public List<StoreRepositoryInventory.Pin> pins(String repository) {
        return inventory(repository).pinned();
    }

    /** The ecosystems this repository's inventory already names, suggested (not enforced) by the pin form. */
    public SortedSet<String> ecosystems(String repository) throws IOException {
        StoreRepositoryInventory inventory = inventory(repository);
        // The first level of the publish facts, never a walk of the coordinates.
        SortedSet<String> ecosystems = new TreeSet<>(inventory.ecosystems());
        for (StoreRepositoryInventory.Pin pin : inventory.pinned()) {
            ecosystems.add(pin.ecosystem());
        }
        return ecosystems;
    }

    public void pin(String repository, String ecosystem, String coordinate, String version) throws IOException {
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

    /** The stored cleanup preview, or empty before one ran; {@link #previewCleanup} starts a fresh one. */
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
        RetentionPolicy policy = retention(repository);
        return StoredReport.compute(scope(repository), PREVIEW_REPORT, () -> {
            CleanupPlan plan = sweeper.plan(inventory, policy, Instant.now());
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

    /**
     * Starts the repository's retention sweep and the garbage collection behind it in the background, storing what was
     * evicted and reclaimed as the last cleanup report; answers whether it started. Without a collector the sweep
     * evicts but reclaims nothing.
     */
    public boolean cleanup(String repository) throws IOException {
        RetentionSweeper sweeper = sweeper();
        StoreRepositoryInventory inventory = inventory(repository);
        ArtifactStore store = scope(repository);
        Properties deployment = settings();
        RetentionPolicy policy = retention(repository);
        boolean started = StoredReport.compute(store, CLEANUP_REPORT, () -> observe("cleanup", repository, observation -> {
            CleanupPlan plan = sweeper.sweep(inventory, policy, Instant.now());
            long reclaimed = 0;
            Optional<GarbageCollector> collector = GarbageCollectorProvider.resolve(deployment::getProperty);
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
        return RetentionProvider.resolve(_ -> null)
                .orElseThrow(() -> new IllegalStateException("Retention is not installed on this deployment."));
    }

    private Optional<Staging> stagingFor(String repository) {
        return StagingProvider.resolve(_ -> null).map(factory -> factory.over(scope(repository)));
    }

    /** A staging repository as the console lists it: its id, lifecycle state, and how many files it holds. */
    public record StagingView(String id, String state, int items) {
    }

}
