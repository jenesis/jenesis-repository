package build.jenesis.repository.maintenance;

import module java.base;

/**
 * The last {@link MaintenanceTaskProvider#resolveContained(UnaryOperator) contained resolve}'s failures (a
 * {@linkplain MaintenanceTaskProvider#resolve(UnaryOperator) strict} one records nothing but an empty map, since it
 * throws rather than return with a provider unresolved), held package-privately
 * so the SPI keeps a method ({@link MaintenanceTaskProvider#unavailable()}) rather than a mutable field - a field on an
 * interface is implicitly public API, and this is a diagnostic snapshot, not part of the contract an implementor writes
 * against.
 *
 * <p>Deployment-scoped rather than per-instance because {@code resolve} is a static over the one
 * {@link ServiceLoader} graph, exactly like the log-once set {@code IntervalSetting} keeps and the installed-scheduler
 * holder {@code MaintenanceObservability} keeps. The map is replaced <em>wholesale</em> on every resolve and handed out
 * unmodifiable, so a reader never sees a half-updated view and a provider that starts resolving again drops out of the
 * report on the next settings-convergence tick instead of being reported failed forever.
 */
final class MaintenanceResolution {

    private static final AtomicReference<Map<String, String>> UNAVAILABLE = new AtomicReference<>(Map.of());

    private MaintenanceResolution() {
    }

    /** Replace the reported failures with this resolve's, wholesale. */
    static void record(Map<String, String> unavailable) {
        UNAVAILABLE.set(Map.copyOf(unavailable));
    }

    /** The providers whose {@code create} failed on the last resolve, keyed by name, with a one-line cause. */
    static Map<String, String> unavailable() {
        return UNAVAILABLE.get();
    }
}
