package build.jenesis.repository.server.kernel;

import module java.base;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;
import build.jenesis.repository.observation.ObservabilitySource;
import build.jenesis.repository.observation.Signals;
import build.jenesis.repository.observation.TaskStatus;

/**
 * The discovered {@link ObservabilitySource} for the maintenance scheduler's {@code ServiceLoader}-discovered
 * background passes (GC / reclamation, the sizes and dependents sweeps, the scheduled scan and cleanup, the forwarding
 * watermark, the continuous re-analysis sweep, ...): a thin, {@link ServiceLoader}-instantiated adapter that reports one
 * {@link TaskStatus} per <em>enabled</em> task of the {@linkplain #install(MaintenanceScheduler) installed} live
 * {@link MaintenanceScheduler}, so an operator - or a headless agent following the console - sees each background
 * sweep's last-run and outcome rather than trusting the worker thread to stay up unnoticed. Because the scheduler owns
 * every task's execution, this one adapter surfaces the status of them all; the individual {@code MaintenanceTask}
 * plugins are untouched.
 *
 * <p>Kept separate from the scheduler (which has no no-arg constructor - it needs its repositories, store and lease
 * ttl) so the {@link ServiceLoader} entry has no state of its own and simply forwards to the one live scheduler the
 * running server holds. It differs from {@code SpoolObservability} in one way that matters: there the installed
 * subject is an SPI with its own {@code SpoolStore.installed()} discovery static, so the adapter is a separate class
 * reading it, whereas here the subject is a single registered instance and {@link #taskStatuses()} reads the field
 * directly. It carried a package-private {@code installed()} accessor whose javadoc named {@code taskStatuses()} as
 * its reader while {@code taskStatuses()} read the field around it - a dead accessor with a false reader, removed by
 * the earlier census; there is no discovery here to have a capability signal about. With no
 * scheduler installed - never wired, or a deployment with the kernel absent - it contributes nothing, so the overview
 * never lists a maintenance signal for something that is not running.
 *
 * <p>A scheduler that <em>is</em> installed always contributes one row for the worker loop itself
 * ({@code jenreg.maintenance.worker}), even with no pass enabled, which is a deliberate change of that rule:
 * "no maintenance pass is enabled" and "the worker died and every pass stopped" are the two readings this surface
 * exists to separate, and a missing row cannot say either. The per-task rows below it remain exactly as they were -
 * one per enabled task, plus the contained-provider failures.
 */
public final class MaintenanceObservability implements ObservabilitySource {

    /** The maintenance feature name every task's signal is composed under ({@code jenreg.maintenance.<task>}). */
    private static final String FEATURE = "maintenance";

    /** The live scheduler the running server holds, so the stateless {@link ServiceLoader} adapter reports its enabled
     *  tasks' last-run / status; empty until {@link #install(MaintenanceScheduler)} runs (a deployment that never wires
     *  the maintenance kernel reports nothing, degrading gracefully). */
    private static final AtomicReference<MaintenanceScheduler> INSTALLED = new AtomicReference<>();

    public MaintenanceObservability() {
    }

    /** Register {@code scheduler} as the live maintenance scheduler this adapter reports; the last registration wins
     *  (there is one scheduler per running server). */
    public static void install(MaintenanceScheduler scheduler) {
        INSTALLED.set(Objects.requireNonNull(scheduler, "scheduler"));
    }

    @Override
    public List<TaskStatus> taskStatuses() {
        MaintenanceScheduler scheduler = INSTALLED.get();
        if (scheduler == null) {
            return List.of();
        }
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
        MaintenanceTaskProvider.unavailable().forEach((task, cause) -> statuses.add(unavailable(task, cause)));
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
     *       reads as liveness rather than as work - the property records the drain depth gauges lacking.</li>
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
