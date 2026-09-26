package build.jenesis.repository.server.kernel;

import module java.base;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;
import build.jenesis.repository.observation.ObservabilitySource;
import build.jenesis.repository.observation.Signals;
import build.jenesis.repository.observation.TaskStatus;

/**
 * The maintenance scheduler's signals: one {@link TaskStatus} per <em>enabled</em> background pass the scheduler runs
 * (GC and reclamation, the sizes and dependents sweeps, the scheduled scan and cleanup, the forwarding watermark, the
 * continuous re-analysis sweep, ...), so an operator - or a headless agent following the console - sees each pass's
 * last run and outcome rather than trusting the worker thread to stay up unnoticed. Because the scheduler owns every
 * task's execution, this one source reports them all; the individual {@code MaintenanceTask} plugins are untouched.
 *
 * <p>It is the scheduler's own face, built beside it by the context that built the scheduler and reported from that
 * context, so a report always describes the scheduler that is running there.
 *
 * <p>The scheduler always contributes one row for the worker loop itself
 * ({@code jenreg.maintenance.worker}), even with no pass enabled, which is a deliberate change of that rule:
 * "no maintenance pass is enabled" and "the worker died and every pass stopped" are the two readings this surface
 * exists to separate, and a missing row cannot say either. The per-task rows below it remain exactly as they were -
 * one per enabled task, plus the contained-provider failures.
 */
public final class MaintenanceObservability implements ObservabilitySource {

    /** The maintenance feature name every task's signal is composed under ({@code jenreg.maintenance.<task>}). */
    private static final String FEATURE = "maintenance";

    private final MaintenanceScheduler scheduler;

    public MaintenanceObservability(MaintenanceScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public List<TaskStatus> taskStatuses() {
        Map<String, TaskSchedule.TaskRun> runs = scheduler.taskRuns();
        List<TaskStatus> statuses = new ArrayList<>();
        // The worker loop itself, before any pass: a task that has never been due reports UNKNOWN whether the worker
        // is iterating every thirty seconds or died an hour ago, so without this row "maintenance is not running" and
        // "maintenance found nothing to do" read identically on the one surface built to tell them apart.
        statuses.add(worker(scheduler.worker()));
        for (String task : scheduler.tasks()) {
            statuses.add(status(task, runs.get(task)));
        }
        // A provider whose create() threw on a settings-convergence tick is CONTAINED by
        // MaintenanceTaskProvider.resolveContained so the running deployment's other passes still schedule - but a
        // contained pass must be reported FAILED, not silently absent from a shorter list, or the containment would
        // trade a loud failure for exactly the silently-incomplete state §5 forbids. (At boot there is nothing to
        // report: MaintenanceTaskProvider.resolve is strict, so a provider that cannot build fails the context.)
        scheduler.unavailable().forEach((task, cause) -> statuses.add(unavailable(task, cause)));
        return statuses;
    }

    /**
     * The worker loop's own status - the row that answers "is background maintenance running at all", separately from
     * what any one pass last did.
     *
     * <ul>
     *   <li>No pass enabled: {@code DISABLED}. The deployment has no background maintenance by configuration, which is
     *       a different statement from a worker that should be running and is not.</li>
     *   <li>Enabled and the thread is alive: {@code IDLE}, stamped with the instant the loop last <em>completed</em> a
     *       scheduling iteration. That stamp advances every idle-poll window whether or not a pass was due, so it
     *       reads as liveness rather than as work - the property the drain depth gauges were found to lack.</li>
     *   <li>Enabled and the thread is not alive: {@code FAILED}, naming why it stopped. Every sweep, drain and GC on
     *       this node is stopped, and that is the sentence an operator needs to read.</li>
     * </ul>
     */
    private static TaskStatus worker(MaintenanceScheduler.Worker worker) {
        String name = Signals.name(FEATURE, "worker");
        String description = "The one background-maintenance worker loop on this node: it owns the thread every "
                + "scheduled sweep, drain and garbage collection runs on, so its own liveness is reported here "
                + "rather than inferred from whether some pass happens to have run recently.";
        if (!worker.enabled()) {
            return TaskStatus.ran(name, description, TaskStatus.State.DISABLED, worker.lastIteration(), null,
                    "no maintenance pass is installed and enabled, so the worker has nothing to schedule");
        }
        if (!worker.alive()) {
            return TaskStatus.ran(name, description, TaskStatus.State.FAILED, worker.lastIteration(), null,
                    "the maintenance worker is NOT running (" + worker.stopped() + ") - no scheduled sweep, drain or "
                            + "garbage collection runs on this node until a settings refresh or a restart");
        }
        return TaskStatus.ran(name, description, TaskStatus.State.IDLE, worker.lastIteration(), null,
                "running; " + worker.iterations() + " scheduling iteration(s) completed. An idle iteration is still "
                        + "an iteration, so this instant advancing is what distinguishes a worker with nothing to do "
                        + "from one that has stopped");
    }

    /** The status of a pass that is installed and enabled but could not be built: its provider threw while reading its
     *  own configuration, so it is not scheduled at all. Reported FAILED with the cause, so an operator sees
     *  "not scheduled - ..." rather than a task list that quietly got shorter. */
    private static TaskStatus unavailable(String task, String cause) {
        return TaskStatus.ran(signal(task),
                "Scheduled background maintenance pass '" + task + "', which this deployment could not build.",
                TaskStatus.State.FAILED, null, null,
                "not scheduled: its provider failed to build the task (" + cause + "). Every other maintenance pass "
                        + "is unaffected; fix the setting it reads and the pass is picked up on the next "
                        + "settings-convergence tick.");
    }

    /** The self-describing status of one enabled task: pending ({@code UNKNOWN}) when it has never finished a run and
     *  nothing has failed, {@code FAILED} once a failure was counted against it (stamped with the last attempt), else
     *  {@code IDLE} between clean runs (stamped with the last-run instant and how long it took). */
    private static TaskStatus status(String task, TaskSchedule.TaskRun run) {
        String name = signal(task);
        String description = "Scheduled background maintenance pass '" + task + "', run across every tenant and "
                + "repository by the one maintenance scheduler; its last run and outcome are surfaced here so an "
                + "operator sees the sweep actually ran rather than trusting the worker thread to stay up unnoticed.";
        if (run == null || (run.finished() == null && !run.failed())) {
            return TaskStatus.ran(name, description, TaskStatus.State.UNKNOWN, null, null,
                    "enabled but has not completed a run yet on this node");
        }
        if (run.failed()) {
            Instant lastRun = run.finished() != null ? run.finished() : run.started();
            return TaskStatus.ran(name, description, TaskStatus.State.FAILED, lastRun, run.duration(),
                    "last run failed (" + run.failures() + " failure(s) counted for this task)");
        }
        return TaskStatus.ran(name, description, TaskStatus.State.IDLE, run.finished(), run.duration(),
                "last run completed cleanly; " + run.reads() + " read and " + run.writes()
                        + " write operations on this node while it ran");
    }

    /**
     * Compose a well-formed {@code jenreg.maintenance.<task...>} signal from a task name. A maintenance task name may
     * carry hyphens ({@code build-scan-retention}, {@code kev-enforce}) that are not a legal {@link Signals} segment on
     * their own, so each run of non-{@code [a-z0-9]} characters splits the name into further dot segments (a
     * {@code kev-enforce} task becomes {@code jenreg.maintenance.kev.enforce}); a segment that would start with a digit
     * is prefixed so it stays a legal segment, and an empty result falls back to {@code task}. The final name is
     * validated by {@link Signals#name} at construction.
     */
    private static String signal(String task) {
        List<String> segments = new ArrayList<>();
        for (String part : task.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (part.isEmpty()) {
                continue;
            }
            segments.add(Character.isLetter(part.charAt(0)) ? part : "t" + part);
        }
        if (segments.isEmpty()) {
            segments.add("task");
        }
        return Signals.name(FEATURE, segments.toArray(new String[0]));
    }
}
