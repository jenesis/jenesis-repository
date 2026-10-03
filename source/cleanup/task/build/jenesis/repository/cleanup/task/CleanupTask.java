package build.jenesis.repository.cleanup.task;

import module java.base;
import module org.slf4j;
import module tools.jackson.databind;
import build.jenesis.repository.gc.GarbageCollector;
import build.jenesis.repository.gc.GcPlan;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.maintenance.TenantContext;
import build.jenesis.repository.maintenance.RetentionSetting;
import build.jenesis.repository.store.JobState;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The scheduled cleanup: finished import and export jobs past their TTL are dismissed per repository, and a quota'd
 * tenant's usage counter is reconciled afterwards - the cleanup legs that are neither a walk nor a repair. Retention,
 * garbage collection and the browse's subtree-size roll-up are walk consumers ({@link RetentionConsumer},
 * {@code GcConsumer}, {@code RollUpConsumer}) that the walks setting's {@code retention} entry carries daily by
 * default. This task's interval is the reaps' cadence, and its lease the single writer a reap needs.
 */
public final class CleanupTask implements MaintenanceTask {

    /** How long a finished import job sits before it is dismissed: a week unless configured; blank, zero or negative
     *  keeps every job. */
    static final RetentionSetting IMPORT_JOB_TTL = RetentionSetting.of("import-job-ttl", "P7D");

    /** The same for a finished export job, on its own dial. */
    static final RetentionSetting EXPORT_JOB_TTL = RetentionSetting.of("export-job-ttl", "P7D");

    /** A kind of job record the sweep dismisses: where its records live, where it stamps first seeing one terminal, the
     *  companion records dismissed with it, and its TTL dial. */
    private record Jobs(String records, String expiry, List<String> companions, RetentionSetting ttl) {
    }

    /** Migration imports, with the console's remembered source; exports, which have no companion. */
    private static final List<Jobs> JOBS = List.of(
            new Jobs("imports", "import-expiry", List.of("import-source"), IMPORT_JOB_TTL),
            new Jobs("exports", "export-expiry", List.of(), EXPORT_JOB_TTL));

    private static final Logger LOGGER = LoggerFactory.getLogger(CleanupTask.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The record a reap leaves between its compare-and-set and its delete. */
    private static final byte[] DISMISSED = ("{\"state\":\"" + JobState.DISMISSED + "\"}")
            .getBytes(StandardCharsets.UTF_8);

    /** The names under {@code prefix}, drained a page at a time. */
    private static List<String> names(ArtifactStore store, String prefix) {
        List<String> names = new ArrayList<>();
        String after = "";
        while (true) {
            List<String> page = new ArrayList<>();
            store.page(prefix, after, ArtifactStore.DRAIN_PAGE, page::add);
            names.addAll(page);
            if (page.size() < ArtifactStore.DRAIN_PAGE) {
                return names;
            }
            after = page.getLast();
        }
    }

    private final Duration interval;

    public CleanupTask(Duration interval) {
        this.interval = interval;
    }

    @Override
    public String name() {
        return "cleanup";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    /** {@link MaintenanceTask.Exclusion#LEASE}: the single writer a reap needs. */
    @Override
    public Exclusion exclusion() {
        return Exclusion.LEASE;
    }

    @Override
    public void repository(RepositoryContext context) throws IOException {
        for (Jobs jobs : JOBS) {
            reapJobs(context, jobs);
        }
    }

    /**
     * Dismiss the repository's finished jobs of one kind: a job no longer running - one that ended, or whose run no
     * longer holds it ({@link JobState#effective}) - its {@code imports/<id>}
     * record and remembered {@code import-source/<id>}, or its {@code exports/<id>} record - is removed once it has sat
     * terminal for its kind's TTL, so one-shot jobs do not accumulate forever. The record carries no timestamp, so the
     * sweep stamps an expiry marker ({@code import-expiry/<id>}, {@code export-expiry/<id>}) when it first sees the
     * terminal state and dismisses a full TTL later - never sooner, and idempotent. A marker whose job was dismissed by
     * hand is dropped; one whose job is running again is reset. Each read is a small status object, and both namespaces
     * drain a page at a time.
     *
     * <p>The store has no conditional delete, so a reap is decided by compare-and-set: the record is set to
     * {@link JobState#DISMISSED} against the very record the age check read, then deleted. A resume landing in between
     * changes the record, so the compare-and-set loses and the job stays running; a later resume finds it dismissed
     * (see {@link JobState}).
     */
    private void reapJobs(RepositoryContext context, Jobs jobs) throws IOException {
        Duration ttl = jobs.ttl().resolve(context.config()).orElse(null);
        if (ttl == null) {
            return;
        }
        ArtifactStore store = context.store();
        for (String id : names(store, jobs.records())) {
            Optional<ArtifactStore.Versioned> job = store.readVersioned(jobs.records() + "/" + id);
            if (job.isEmpty()) {
                continue;
            }
            String state;
            try {
                state = JSON.readTree(new String(job.get().content(), StandardCharsets.UTF_8))
                        .path("state").asString(null);
            } catch (RuntimeException _) {
                continue;                                        // not a job record we understand - never delete it
            }
            String expiry = jobs.expiry() + "/" + id;
            // A running record no run holds is an interrupted job, finished as far as a reap is concerned.
            if (state == null || JobState.RUNNING.equals(JobState.effective(store, jobs.records(), id, state))) {
                store.delete(expiry);                            // a resumed job runs again; its old marker is stale
                continue;
            }
            Optional<ArtifactStore.Versioned> seen = store.readVersioned(expiry);
            if (seen.isEmpty()) {
                // Create-if-absent: a lost race means another sweeper created the marker, which starts the same clock -
                // convergence, not a lost update, so it is not retried.
                var _ = store.writeVersioned(expiry, context.now().toString().getBytes(StandardCharsets.UTF_8), null);
                continue;
            }
            Instant since;
            try {
                since = Instant.parse(new String(seen.get().content(), StandardCharsets.UTF_8).trim());
            } catch (java.time.format.DateTimeParseException _) {  // qualified: jackson's module exports a homonym
                continue;
            }
            if (Duration.between(since, context.now()).compareTo(ttl) >= 0) {
                if (!store.writeVersioned(jobs.records() + "/" + id, DISMISSED, job.get().token())) {
                    continue;                                    // resumed since it was read - a later pass reaps it
                }
                store.delete(jobs.records() + "/" + id);
                for (String companion : jobs.companions()) {
                    store.delete(companion + "/" + id);
                }
                store.delete(expiry);
            }
        }
        for (String id : names(store, jobs.expiry())) {
            if (store.readVersioned(jobs.records() + "/" + id).isEmpty()) {
                store.delete(jobs.expiry() + "/" + id);          // the job was dismissed by hand; the marker goes too
            }
        }
    }

    @Override
    public void tenant(TenantContext context) throws IOException {
        if (context.quotaLimit() > 0) {
            context.recomputeQuota();
        }
    }
}
