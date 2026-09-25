package build.jenesis.repository.cleanup.task;

import module java.base;
import module org.slf4j;
import module tools.jackson.databind;
import build.jenesis.repository.cleanup.RetentionPolicy;
import build.jenesis.repository.gc.GarbageCollector;
import build.jenesis.repository.gc.GcPlan;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.maintenance.TenantContext;
import build.jenesis.repository.maintenance.RetentionSetting;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.ArtifactWalk;

/**
 * The scheduled cleanup: finished import jobs past their TTL are auto-dismissed per repository, and a quota'd
 * tenant's usage counter is reconciled afterwards - the two legs of the old pass that are neither a walk nor a
 * repair. Retention, garbage collection and the browse's subtree-size roll-up left this pass for the walk: the
 * {@link RetentionConsumer} judges the inventory rows as the walk streams them, the {@code GcConsumer} and the
 * {@code RollUpConsumer} run at the end of a pass that carries them, and the {@code retention} entry of the walks
 * setting carries all three daily by default. So this task's interval is the reaps' cadence, daily by default, and
 * its lease is the single-writer one a reap needs.
 */
public final class CleanupTask implements MaintenanceTask {

    /** How long a finished import job sits before the sweep dismisses it: a week unless configured, and blank, zero
     *  or negative keeps every job. */
    static final RetentionSetting IMPORT_JOB_TTL = RetentionSetting.of("import-job-ttl", "P7D");

    /** The same for a finished export job, on its own dial so an operator may keep one kind's records longer. */
    static final RetentionSetting EXPORT_JOB_TTL = RetentionSetting.of("export-job-ttl", "P7D");

    /** A kind of job record the sweep dismisses: where its records live, where it stamps when it first saw one
     *  terminal, the companion records dismissed with it, and the dial that says how long it sits. */
    private record Jobs(String records, String expiry, List<String> companions, RetentionSetting ttl) {
    }

    /** Migration imports, with the console's remembered source; export jobs, which have no companion. */
    private static final List<Jobs> JOBS = List.of(
            new Jobs("imports", "import-expiry", List.of("import-source"), IMPORT_JOB_TTL),
            new Jobs("exports", "export-expiry", List.of(), EXPORT_JOB_TTL));

    private static final Logger LOGGER = LoggerFactory.getLogger(CleanupTask.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

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

    /** {@link MaintenanceTask.Exclusion#LEASE} only in the no-walk degrade: with the shared walk installed every
     *  leg cooperates on compare-and-set-claimed segments (retention, roll-up) or is provider-resolved onto that same
     *  walk (the collector), so replicated nodes join one pass on disjoint ranges instead of single-writing per
     *  interval. */
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
     * Auto-dismiss the repository's finished jobs of one kind - migration imports and exports alike: a job whose state
     * is no longer {@code running} - its {@code imports/<id>} record and the console's remembered
     * {@code import-source/<id>}, or its {@code exports/<id>} record - is removed once it has sat terminal for its
     * kind's TTL, so a fleet of one-shot jobs does not accumulate a job object per run forever (before this, only a
     * manual dismiss removed them). The job record carries no timestamp, so the sweep stamps an expiry marker
     * ({@code import-expiry/<id>}, {@code export-expiry/<id>}) when it <em>first observes</em> the
     * terminal state and dismisses a full TTL later - conservative (never sooner than the TTL after finishing) and
     * idempotent. A marker whose job was manually dismissed is dropped; one whose job is running again (a resume)
     * is reset. Each read is the small JSON status object, never an artifact.
     */
    private void reapJobs(RepositoryContext context, Jobs jobs) throws IOException {
        Duration ttl = jobs.ttl().resolve(context.config()).orElse(null);
        if (ttl == null) {
            return;
        }
        ArtifactStore store = context.store();
        for (String id : store.list(jobs.records())) {
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
            if (state == null || state.equals("running")) {
                store.delete(expiry);                            // a resumed job runs again; its old marker is stale
                continue;
            }
            Optional<ArtifactStore.Versioned> seen = store.readVersioned(expiry);
            if (seen.isEmpty()) {
                // Create-if-absent: a false return means a concurrent sweeper already created the marker, whose
                // timestamp serves the same purpose (the expiry clock starts once either way) - the one CAS whose
                // lost race is convergence rather than a lost update, so it is deliberately not retried.
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
                // Re-read the job's state immediately before the destructive delete: a resume that landed in the window
                // between the state read above and here rewrites state=running, and reaping it would destroy a now-live
                // job record. The store has no conditional delete, so this re-read shrinks the lost-update window to the
                // gap before the unconditional delete rather than the whole per-record scan (the marker-recheck pattern).
                Optional<ArtifactStore.Versioned> fresh = store.readVersioned(jobs.records() + "/" + id);
                if (fresh.isPresent()) {
                    String freshState;
                    try {
                        freshState = JSON.readTree(new String(fresh.get().content(), StandardCharsets.UTF_8))
                                .path("state").asString(null);
                    } catch (RuntimeException _) {
                        continue;                                // unreadable now - never delete a record we don't understand
                    }
                    if (freshState == null || freshState.equals("running")) {
                        continue;                                // resumed since the age check - leave it; a later terminal pass reaps
                    }
                }
                store.delete(jobs.records() + "/" + id);
                for (String companion : jobs.companions()) {
                    store.delete(companion + "/" + id);
                }
                store.delete(expiry);
            }
        }
        for (String id : store.list(jobs.expiry())) {
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
