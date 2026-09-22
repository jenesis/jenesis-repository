package build.jenesis.repository.server.kernel;

import module java.base;
import build.jenesis.repository.maintenance.MaintenanceTask;

/**
 * One discovered {@link MaintenanceTask} paired with the two declarations the scheduler reads off it <b>once, at
 * resolution</b>: its {@linkplain MaintenanceTask#name() name} and its {@linkplain MaintenanceTask#interval()
 * cadence}. Every later use - the {@code locks/<name>} single-writer lease, the {@code jenreg.<name>}
 * bookkeeping key, the {@code task} meter tag, the due-time arithmetic and every diagnostic - reads this record
 * rather than re-entering the task.
 *
 * <p><b>Why capture rather than call (the earlier ruling transferred).</b> A handler that asks a broken task what
 * it is called can be defeated from inside its own handler: the {@code MaintenanceScheduler} called
 * {@code task.name()} <em>twice</em> in each of four catch blocks (once to log, once to count), so a task whose
 * {@code name()} threw turned a contained pass failure into an exception escaping the bare
 * {@code jenesis-repository-maintenance} thread - and with it every sweep, drain and GC on the node. Capturing the
 * name at resolution is what makes containment unbreakable, exactly as {@code EventSink.emit} pairs each sink with
 * the name resolution read from it before any sink is called.
 *
 * <p>The name is more than a diagnostic here, which is the second reason: it is the lease object an exclusive pass
 * locks on and the key the schedule and the failure counter are kept under, so a task that answered differently on
 * two calls could take one lease and report another. One read, one identity, for the life of the resolution.
 *
 * <p>The cadence is captured for the same reason and costs nothing: the SPI already requires a provider to resolve
 * its dial inside {@code create} ({@code IntervalSetting}), and a dial changed through the settings re-creates the
 * task on the next {@code refresh()}, so the captured value is the same value {@code interval()} would return - but
 * it can no longer be read from inside {@code TaskSchedule}, which runs <em>outside</em> the per-task containment and
 * would take the loop down with it.
 *
 * <p>A task that cannot be named or timed is refused here, at resolution, naming its class - it can neither take a
 * lease nor be reported, so scheduling it would be scheduling something the deployment cannot talk about. The refusal
 * is an {@link IllegalStateException}, which is the shape {@code MaintenanceTaskProvider} already documents for a
 * packaging error: fatal at boot (the scheduler is constructed inside a {@code @Bean} method) and contained on a
 * settings-convergence tick, where {@code SettingsRefresh} keeps the last resolved list whole and logs.
 */
record ScheduledTask(String name, Duration interval, MaintenanceTask task) {

    ScheduledTask {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(task, "task");
    }

    /** Read {@code task}'s name and cadence once, refusing a task that cannot supply either. */
    static ScheduledTask of(MaintenanceTask task) {
        Objects.requireNonNull(task, "task");
        String name = read(task, "name", task::name);
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("Maintenance task " + task.getClass().getName() + " has a blank name. A "
                    + "task name is its jenreg.<name> toggle, its locks/<name> single-writer lease object "
                    + "and the key its run status and failure counter are kept under, so an unnamed task cannot be "
                    + "scheduled, locked or reported.");
        }
        Duration interval = read(task, "interval", task::interval);
        if (interval == null) {
            throw new IllegalStateException("Maintenance task '" + name + "' (" + task.getClass().getName()
                    + ") declares no interval. The cadence decides when the pass is due; null is never a legal "
                    + "return.");
        }
        return new ScheduledTask(name, interval, task);
    }

    /** Read one declaration, turning any failure - including an {@link Error} out of a module graph that cannot
     *  supply the class - into the resolution refusal above, so a broken declaration is refused where it can still be
     *  attributed to a class rather than defeating the containment of a loop it has already entered. */
    private static <T> T read(MaintenanceTask task, String declaration, Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Throwable broken) {
            throw new IllegalStateException("Maintenance task " + task.getClass().getName() + " could not supply its "
                    + declaration + "(): " + broken, broken);
        }
    }
}
