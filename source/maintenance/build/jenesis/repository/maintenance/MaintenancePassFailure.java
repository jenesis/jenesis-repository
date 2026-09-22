package build.jenesis.repository.maintenance;

/**
 * A marker suppressed onto an {@link Error} that came out of one {@link MaintenanceTaskProvider}, naming which one.
 *
 * <p>It exists because maintenance resolution deliberately does <em>not</em> contain an {@link Error}. A provider's
 * own value or construction failure is contained on a refresh - that provider disables itself and every other pass
 * still schedules - but an {@code Error} is the runtime or the module graph giving way (a {@code LinkageError} from a
 * half-installed plugin) rather than one pass failing to build, so it reaches the caller instead of leaving a
 * deployment that looks whole while a pass is silently absent. That is the same line {@code Contributions} draws for
 * the observability surfaces, and it is the right one.
 *
 * <p>The cost of propagating was attribution. The loop caught {@link RuntimeException} only, so an {@code Error}
 * left carrying nothing that said <em>which</em> of the installed providers raised it: an operator whose boot failed
 * learned that maintenance resolution died and not which plugin killed it, and with two dozen passes installed that
 * is the difference between a name and a bisect.
 *
 * <p><b>Why a suppressed marker rather than a wrapper.</b> Wrapping would change what the caller catches, and an
 * {@code Error} is precisely the thing that must keep its type on the way out - Spring's context failure, the
 * scheduler's error handler and every operator runbook key off it. Suppression adds the name without touching the
 * type, the message or the cause chain, and it prints with the stack trace, so the attribution reaches every log that
 * already renders one. The walk's pass used the same shape for a consumer's failure until a consumer's failure
 * became a durable record of its own ({@code RebuildPass.failed}), which is what an operator reads now.
 */
public final class MaintenancePassFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param name     the pass name read before anything was decided on it, or {@code null} when the provider could
     *                 not be named at all.
     * @param provider the provider whose {@code create} raised the {@link Error}.
     */
    MaintenancePassFailure(String name, MaintenanceTaskProvider provider) {
        super("raised by maintenance pass " + (name == null || name.isBlank()
                ? provider.getClass().getName()
                : name + " (" + provider.getClass().getName() + ")"), null, false, false);
    }
}
