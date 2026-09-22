package build.jenesis.repository.server.kernel;

import module java.base;
import build.jenesis.repository.store.metering.MeteringArtifactStore;
import build.jenesis.repository.maintenance.MaintenanceTask;

/**
 * The maintenance scheduler's <em>due-time and run bookkeeping</em>, split out of {@link MaintenanceScheduler} so the
 * arithmetic that decides "which pass is due, and how long may the worker sleep" is a deterministic, storeless,
 * threadless object a test can drive against an injected clock (the maintenance-kernel spike's R4/R8).
 *
 * <p>It reads its inputs off {@link ScheduledTask} - the task paired with the name and cadence captured once at
 * resolution - rather than off the {@link MaintenanceTask} itself, and that is load-bearing rather than cosmetic
 *: every method here runs on the one worker loop <em>outside</em> its per-task containment, so a discovered
 * task whose {@code name()} or {@code interval()} threw from in here would take the deployment's only maintenance
 * thread down with it, silently. Nothing in this class calls into a task at all.
 *
 * <p>It owns exactly two pieces of state and nothing else:
 * <ul>
 *   <li>the <strong>due time per task name</strong> - by <em>name</em>, never by instance, so a
 *       {@link MaintenanceScheduler#refresh()} that yields fresh {@link MaintenanceTask} objects keeps each pass on its
 *       own schedule instead of re-arming everything; a task that drops out of the enabled list drops its due time with
 *       it, and one enabled after boot is scheduled one interval out rather than firing immediately; and</li>
 *   <li>the <strong>{@link TaskRun} history and in-flight count per task name</strong> - the read side the discovered
 *       {@code MaintenanceObservability} adapter reports each pass's last run and outcome through.</li>
 * </ul>
 *
 * <p>It deliberately owns <em>no</em> lease ({@link LeaseGuard}), no meter ({@link PassMetrics}), no thread, no store
 * and no tenant knowledge: {@link MaintenanceScheduler} remains the one owner of the worker thread and of the
 * tenant/repository iteration. Splitting this out adds no second scheduler - there is still exactly one loop, and it
 * asks this object what to run.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> {@link #started}, {@link #finished}, {@link #failed} and {@link #runs()} are safe to call
 *       concurrently - the worker loop, an admin {@code runNow} and every fanned-out unit all touch them - and every
 *       stored {@link TaskRun} is an immutable snapshot swapped in wholesale. {@link #sleep} and {@link #due} are
 *       called only from the single worker loop and are <em>not</em> synchronised against each other; they are pure
 *       functions of the arguments plus the due map.</li>
 *   <li><b>Idempotency.</b> {@link #due} re-arms every task it returns, so calling it twice for the same instant
 *       returns the second time only what a re-arm did not cover. That is the point: a pass runs once per interval.</li>
 *   <li><b>Bounded work.</b> {@link #sleep} never returns less than {@link #FLOOR} nor more than {@link #IDLE_POLL},
 *       so a pass configured (or defaulted) to a degenerate cadence cannot spin the worker, and a task toggled on
 *       through the settings is picked up within the idle-poll window rather than only when some other task falls
 *       due.</li>
 * </ol>
 */
public final class TaskSchedule {

    /** The longest the worker sleeps before re-reading the (volatile) task list, so a pass toggled on or off through a
     *  {@link MaintenanceScheduler#refresh()} is picked up within this window rather than only when the next task
     *  happens to fall due. */
    static final Duration IDLE_POLL = Duration.ofSeconds(30);

    /** The shortest the worker sleeps between iterations. {@code IntervalSetting} already refuses a non-positive
     *  cadence, so this is a second, independent floor rather than the only thing standing between a typo and a pass
     *  that re-runs continuously. */
    static final Duration FLOOR = Duration.ofSeconds(1);

    /** Due times keyed by task <em>name</em> (never by instance): a re-resolve that yields fresh task objects keeps
     *  each pass on its own schedule. Touched only by the single worker loop. */
    private final Map<String, Instant> due = new HashMap<>();

    /** Per-task last-run bookkeeping keyed by task name - the worker loop, {@link MaintenanceScheduler#runNow} and the
     *  fanned-out units all touch it, so it is a {@link ConcurrentHashMap} and every stored {@link TaskRun} is an
     *  immutable snapshot swapped in wholesale. */
    private final Map<String, TaskRun> runs = new ConcurrentHashMap<>();

    /** In-flight run count per task name. A run only clears the per-run {@code failed} flag when it begins with no
     *  other run of the same task still outstanding, so an admin {@code runNow} overlapping the scheduled worker on a
     *  non-exclusive task (both drive {@code run()} with no lease) cannot mask a failure its still-running sibling
     *  recorded - the cumulative {@code failures} count is preserved regardless. */
    private final ConcurrentMap<String, Integer> inFlight = new ConcurrentHashMap<>();

    /** The node's operation totals when each task's outstanding run began - see {@link #operations()}. */
    private final Map<String, long[]> atStart = new ConcurrentHashMap<>();

    /**
     * An immutable snapshot of one maintenance task's last run: when the run last {@code started}, when it last
     * {@code finished} (null while a run is in flight, or if none has finished yet), how long that finished run took
     * ({@code duration}, null when unknown), whether a failure was counted against the run ({@code failed} - a unit
     * that threw, the whole pass, or a single-writer lease lost mid-pass), and the cumulative {@code failures} counted
     * against the task. Surfaced through {@link MaintenanceScheduler#taskRuns()} so the discovered
     * {@code MaintenanceObservability} adapter can report each task's last-run / status without the scheduler touching
     * the observation registry.
     */
    public record TaskRun(Instant started, Instant finished, Duration duration, boolean failed, long failures,
                          long reads, long writes) {
    }

    /** An immutable snapshot of every task that has started at least one run on this node, keyed by task name. A task
     *  that has never started a run has no entry (the adapter reports it as pending); a task no longer enabled keeps
     *  its last history here, but the adapter, iterating the enabled tasks, no longer reports it. */
    public Map<String, TaskRun> runs() {
        return Map.copyOf(runs);
    }

    /**
     * How long the worker may sleep before its next iteration: until the earliest task falls due, clamped into
     * [{@link #FLOOR}, {@link #IDLE_POLL}]. Tasks absent from the due map are scheduled one interval out from
     * {@code now} first, and tasks that are no longer enabled drop out - so this call is also where the due map
     * converges on the current task list.
     */
    Duration sleep(List<ScheduledTask> tasks, Instant now) {
        arm(tasks, now);
        if (due.isEmpty()) {
            return IDLE_POLL;
        }
        Duration until = Duration.between(now, Collections.min(due.values()));
        return until.compareTo(FLOOR) < 0 ? FLOOR : until.compareTo(IDLE_POLL) > 0 ? IDLE_POLL : until;
    }

    /** Schedule any task not yet known one interval out, and forget the due time of every task that is no longer
     *  enabled - so a disabled pass stops holding the worker awake and a re-enabled one does not fire immediately. */
    /**
     * A node that ran an exclusive pass yields: it is not due again before {@code notBefore}, one interval after the
     * pass finished, however long the pass took. Re-arming from the moment a task became due - the rule above, right
     * for a pass shorter than its interval - makes a pass longer than its interval due again the instant it ends,
     * and the node that just released the lease re-takes it before a peer polling at the same cadence ever finds it
     * free. Measured 2026-09-06 in the fleet: a restarted node never joined a minute-long walk repeating every two
     * seconds within four minutes. The yield is what hands the lease round.
     */
    synchronized void yield(String task, Instant notBefore) {
        due.merge(task, notBefore, (current, later) -> current.isAfter(later) ? current : later);
    }

    /** When each task is next due on this node, as the schedule stands. */
    synchronized Map<String, Instant> nextDue() {
        return Map.copyOf(due);
    }

    private synchronized void arm(List<ScheduledTask> tasks, Instant now) {
        Set<String> names = new LinkedHashSet<>();
        for (ScheduledTask task : tasks) {
            names.add(task.name());
            due.putIfAbsent(task.name(), task.task().next(now));
        }
        due.keySet().retainAll(names);
    }

    /**
     * The tasks that have fallen due at {@code at}, in the order they were handed in, each re-armed one interval out
     * before it is returned - so a pass that runs long, or throws, is still next due one interval after it became due
     * rather than immediately after it finished. A task the schedule has never seen is armed rather than run, so a pass
     * enabled after boot waits out its first interval instead of firing the moment it is discovered.
     */
    synchronized List<ScheduledTask> due(List<ScheduledTask> tasks, Instant at) {
        List<ScheduledTask> ready = new ArrayList<>();
        for (ScheduledTask task : tasks) {
            Instant taskDue = due.get(task.name());
            if (taskDue == null || at.isBefore(taskDue)) {
                continue;
            }
            due.put(task.name(), task.task().next(at));
            ready.add(task);
        }
        return ready;
    }

    /** Mark {@code task} as having begun a run at {@code now}: a run that begins with no other run of the same task
     *  still outstanding resets the per-run failed flag (a prior finished run's failure never bleeds into this one)
     *  while carrying the previous finished/duration and the cumulative failure count forward. A run that begins while
     *  a sibling run is still in flight (an admin {@code runNow} overlapping the scheduled worker on a non-exclusive
     *  task) does NOT reset the flag, so it cannot mask a failure the sibling recorded. */
    void started(String task, Instant now) {
        boolean firstOutstanding = inFlight.merge(task, 1, Integer::sum) == 1;
        if (firstOutstanding) {
            atStart.put(task, operations());
        }
        runs.compute(task, (name, previous) -> {
            boolean failed = !firstOutstanding && previous != null && previous.failed();
            long failures = previous == null ? 0L : previous.failures();
            Instant finished = previous == null ? null : previous.finished();
            Duration duration = previous == null ? null : previous.duration();
            long reads = previous == null ? 0L : previous.reads();
            long writes = previous == null ? 0L : previous.writes();
            return new TaskRun(now, finished, duration, failed, failures, reads, writes);
        });
    }

    /** The store operations this node has issued so far, as a read-class and a write-class total: what a run's
     *  cost is measured as the difference of. Node-wide, so a run's figure includes whatever else the node did
     *  meanwhile - the traffic it served, another pass - and is an upper bound on the pass's own. */
    private static long[] operations() {
        long reads = 0;
        long writes = 0;
        for (Map.Entry<String, Long> entry : MeteringArtifactStore.operations().entrySet()) {
            if (MeteringArtifactStore.writes(entry.getKey())) {
                writes += entry.getValue();
            } else {
                reads += entry.getValue();
            }
        }
        return new long[] {reads, writes};
    }

    /** Mark {@code task}'s run as finished at {@code now} taking {@code duration}: it records the last-run instant and
     *  duration but <em>preserves</em> any failure counted during the pass (a fanned-out unit that threw, or a lease
     *  lost mid-pass, set the flag before this runs), so a pass with a failing unit is not miscounted as a clean run. */
    void finished(String task, Instant now, Duration duration) {
        Integer outstanding = inFlight.merge(task, -1, (current, delta) -> current + delta == 0 ? null : current + delta);
        long[] before = outstanding == null ? atStart.remove(task) : null;
        long[] after = before == null ? null : operations();
        runs.compute(task, (name, previous) -> {
            boolean failed = previous != null && previous.failed();
            long failures = previous == null ? 0L : previous.failures();
            Instant startedAt = previous == null ? null : previous.started();
            long reads = before == null ? (previous == null ? 0L : previous.reads()) : after[0] - before[0];
            long writes = before == null ? (previous == null ? 0L : previous.writes()) : after[1] - before[1];
            return new TaskRun(startedAt, now, duration, failed, failures, reads, writes);
        });
    }

    /** Record one failure against {@code task}: the per-run flag the observability seam reports FAILED from, and the
     *  cumulative count. Deliberately separate from {@link PassMetrics#failure(String)} so a deployment (or a test)
     *  with no meter registry wired still reports the pass as failed. */
    void failed(String task) {
        runs.compute(task, (name, previous) -> previous == null
                ? new TaskRun(null, null, null, true, 1L, 0L, 0L)
                : new TaskRun(previous.started(), previous.finished(), previous.duration(), true,
                        previous.failures() + 1L, previous.reads(), previous.writes()));
    }
}
