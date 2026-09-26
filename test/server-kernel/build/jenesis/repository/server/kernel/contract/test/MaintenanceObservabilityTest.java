package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.MaintenanceObservability;
import build.jenesis.repository.server.kernel.MaintenanceScheduler;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.observation.ObservabilityReport;
import build.jenesis.repository.observation.TaskStatus;
import build.jenesis.repository.staging.StagingProvider;
import build.jenesis.repository.cleanup.RetentionProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.inventory.StoreRepositoryInventory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MaintenanceObservability}, the scheduler's own face, surfaces each enabled maintenance task's last-run / status
 * through the observation seam: one {@link TaskStatus} per enabled task of the
 * {@link MaintenanceScheduler}, named per the {@code jenreg.maintenance.<task>} grammar. A task that ran cleanly
 * reports an {@code IDLE} status stamped with its last-run instant, a task whose pass failed reports {@code FAILED}, a
 * never-run enabled task reports pending ({@code UNKNOWN}), and a scheduler with no enabled task contributes no task
 * signal. Beside them sits one row for the worker <em>loop</em>
 * ({@code jenreg.maintenance.worker}), which is what separates "no pass is enabled" and "the worker stopped
 * and every pass with it" - two conditions the per-task rows read identically. It is reported from the context that
 * built the scheduler, so two contexts in one JVM each report their own.
 */
class MaintenanceObservabilityTest {

    private static final Instant NOW = Instant.parse("2026-06-27T00:00:00Z");

    @TempDir
    Path root;

    private ArtifactStore store;
    private Repositories repositories;

    @BeforeEach
    void setUp() throws IOException {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        RepositoryProperties properties = new RepositoryProperties();
        properties.setProxyEnabled(false);
        LiveConfig live = new LiveConfig(new Settings(store), properties, AdvisorySource.none(), _ -> null);
        repositories = new Repositories(store, Authorization.anonymous(), live,
                StagingProvider.resolve(_ -> null), RetentionProvider.resolve(_ -> null));
    }

    @Test
    void a_task_that_ran_ok_reports_an_ok_status_with_a_last_run_instant() throws IOException {
        new StoreRepositoryInventory(repositories.store("default", "alpha")).record("Maven", "org.a:lib", "1.0", NOW);
        MaintenanceScheduler scheduler = schedulerWith(task("cleanup-observed", false));
        scheduler.runNow(NOW);

        TaskStatus status = find(new MaintenanceObservability(scheduler).taskStatuses(), "jenreg.maintenance.cleanup.observed");
        assertThat(status.state()).as("a clean run is IDLE between passes, not FAILED").isEqualTo(TaskStatus.State.IDLE);
        assertThat(status.everRan()).as("a completed pass records a last-run instant").isTrue();
        assertThat(status.lastRun()).isNotNull();
        assertThat(status.description()).as("each task status carries a human-readable description").isNotBlank();
    }

    @Test
    void a_failing_task_reports_failed() throws IOException {
        // A repository unit that throws is logged and counted; the task's last-run status must then read FAILED, not
        // be masked as a clean pass by the pass completing around the failed unit.
        new StoreRepositoryInventory(repositories.store("default", "alpha")).record("Maven", "org.a:lib", "1.0", NOW);
        MaintenanceScheduler scheduler = schedulerWith(task("scan", true));
        scheduler.runNow(NOW);

        TaskStatus status = find(new MaintenanceObservability(scheduler).taskStatuses(), "jenreg.maintenance.scan");
        assertThat(status.state()).isEqualTo(TaskStatus.State.FAILED);
        assertThat(status.everRan()).as("the failed attempt is still stamped with a last-run instant").isTrue();
    }

    @Test
    void a_never_run_enabled_task_reports_pending() {
        MaintenanceScheduler scheduler = schedulerWith(task("reanalyze", false));
        // No runNow: the task is enabled but has not run yet.

        TaskStatus status = find(new MaintenanceObservability(scheduler).taskStatuses(), "jenreg.maintenance.reanalyze");
        assertThat(status.state()).as("a never-run enabled task is pending, not IDLE or FAILED")
                .isEqualTo(TaskStatus.State.UNKNOWN);
        assertThat(status.everRan()).isFalse();
        assertThat(status.lastRun()).isNull();
    }

    @Test
    void a_disabled_scheduler_reports_the_worker_as_disabled_and_no_task() {
        // A scheduler with no enabled task contributes no TASK signal - the overview never lists a maintenance signal
        // for something that is not running. The adapter also reports any provider whose create() a
        // settings-convergence tick CONTAINED, so this states the whole "nothing is running" condition: nothing
        // enabled AND nothing failed to build. Resolving a configuration that enables no pass is how a deployment
        // reaches that state.
        //
        // What it DOES contribute is the worker row, and that is the deliberate change: "no maintenance pass
        // is enabled" and "the worker died and every pass stopped" are the two readings this surface exists to
        // separate, and an empty list said neither. The row says DISABLED here, which is the first of them.
        MaintenanceTaskProvider.resolve(key -> null);
        List<TaskStatus> statuses = new MaintenanceObservability(schedulerWith()).taskStatuses();
        assertThat(statuses).extracting(TaskStatus::name)
                .as("the worker row, and nothing else - no pass is enabled to report")
                .containsExactly("jenreg.maintenance.worker");
        assertThat(find(statuses, "jenreg.maintenance.worker").state())
                .as("no pass enabled is DISABLED, not FAILED - the worker has nothing to schedule")
                .isEqualTo(TaskStatus.State.DISABLED);
    }

    @Test
    void the_worker_row_tells_a_stopped_loop_from_an_idle_one() throws IOException, InterruptedException {
        // The operator's question, on the one surface built to answer it: an enabled task that has never been due
        // reports UNKNOWN whether the worker is iterating every idle-poll window or died an hour ago, so the loop's
        // own liveness has to be a row of its own.
        MaintenanceScheduler scheduler = schedulerWith(task("reanalyze", false));

        TaskStatus stopped = find(new MaintenanceObservability(scheduler).taskStatuses(), "jenreg.maintenance.worker");
        assertThat(stopped.state())
                .as("an enabled scheduler whose worker is not running is FAILED, however quiet the task rows are")
                .isEqualTo(TaskStatus.State.FAILED);
        assertThat(stopped.outcome()).as("and it says so in words an operator can act on")
                .contains("NOT running").contains("not started");

        scheduler.start();
        try {
            // The worker stamps its first iteration on its own thread, so wait for it by attempts rather than by a
            // deadline: a loaded machine makes the wait longer, not weaker.
            for (int attempt = 0; attempt < 2_000 && scheduler.worker().lastIteration() == null; attempt++) {
                Thread.sleep(10L);
            }
            TaskStatus running = find(new MaintenanceObservability(scheduler).taskStatuses(), "jenreg.maintenance.worker");
            assertThat(running.state())
                    .as("a started worker with nothing due is IDLE - it found nothing to do, which is not the same "
                            + "thing as not running")
                    .isEqualTo(TaskStatus.State.IDLE);
            assertThat(running.lastRun())
                    .as("stamped with the loop's own iteration, so the reading is liveness rather than work")
                    .isNotNull();
            assertThat(find(new MaintenanceObservability(scheduler).taskStatuses(), "jenreg.maintenance.reanalyze").state())
                    .as("while the pass itself is still pending, exactly as before")
                    .isEqualTo(TaskStatus.State.UNKNOWN);
        } finally {
            scheduler.close();
        }
    }

    @Test
    void the_report_of_the_context_that_built_the_scheduler_carries_its_task_statuses() throws IOException {
        new StoreRepositoryInventory(repositories.store("default", "alpha")).record("Maven", "org.a:lib", "1.0", NOW);
        MaintenanceScheduler scheduler = schedulerWith(task("forwarding", false));
        scheduler.runNow(NOW);
        MaintenanceScheduler another = schedulerWith(task("elsewhere", false));

        ObservabilityReport report = ObservabilityReport.of(List.of(new MaintenanceObservability(scheduler)));
        assertThat(report.tasks().stream().map(TaskStatus::name))
                .as("a context reports the scheduler it built")
                .contains("jenreg.maintenance.forwarding")
                .doesNotContain("jenreg.maintenance.elsewhere");
        assertThat(ObservabilityReport.of(List.of(new MaintenanceObservability(another))).tasks().stream()
                .map(TaskStatus::name))
                .as("and a second context in the same JVM reports its own")
                .contains("jenreg.maintenance.elsewhere")
                .doesNotContain("jenreg.maintenance.forwarding");
    }

    private MaintenanceScheduler schedulerWith(MaintenanceTask... tasks) {
        return new MaintenanceScheduler(repositories, store, List.of(tasks), key -> null,
                Duration.ofMinutes(10), null);
    }

    private static TaskStatus find(List<TaskStatus> statuses, String name) {
        return statuses.stream()
                .filter(status -> status.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No task status named " + name + " in " + statuses));
    }

    /** A per-node (non-exclusive, so {@code runNow} drives it without a lease) maintenance task that either succeeds
     *  silently or throws from every repository unit. */
    private static MaintenanceTask task(String name, boolean fail) {
        return new MaintenanceTask() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Duration interval() {
                return Duration.ofMinutes(10);
            }

            @Override
            public Exclusion exclusion() {
                return Exclusion.NONE;
            }

            @Override
            public void repository(RepositoryContext context) {
                if (fail) {
                    throw new IllegalStateException("boom");
                }
            }
        };
    }
}
