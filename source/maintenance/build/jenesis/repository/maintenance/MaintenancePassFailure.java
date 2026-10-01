package build.jenesis.repository.maintenance;

/**
 * A marker suppressed onto an {@link Error} that came out of one {@link MaintenanceTaskProvider}, naming which one.
 * Maintenance resolution does not contain an {@code Error}, since it is the runtime or module graph giving way rather
 * than one pass failing to build. Suppression names the provider without changing the error's type, message or cause
 * chain, and prints with the stack trace.
 */
public final class MaintenancePassFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param name     the pass name, or {@code null} when the provider could not be named.
     * @param provider the provider whose {@code create} raised the {@link Error}.
     */
    MaintenancePassFailure(String name, MaintenanceTaskProvider provider) {
        super("raised by maintenance pass " + (name == null || name.isBlank()
                ? provider.getClass().getName()
                : name + " (" + provider.getClass().getName() + ")"), null, false, false);
    }
}
