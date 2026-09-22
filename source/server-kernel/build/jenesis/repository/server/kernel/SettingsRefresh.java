package build.jenesis.repository.server.kernel;

import module java.base;
import module org.slf4j;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The scheduled convergence pass for the store-backed runtime configuration. On the refresh interval it re-reads the
 * stored settings and, only when they have changed since it last converged, re-seeds the two runtime surfaces a write
 * on another node would otherwise leave stale on this one:
 *
 * <ul>
 *   <li>the {@link LiveConfig} live snapshot - the compliance gate and the proxy/retention/default dials, rebuilt
 *       through {@code resolve(config)} - which converges the keys the hot paths read live; and</li>
 *   <li>the mutable {@link org.springframework.core.env.Environment}'s stored-settings property source
 *       ({@link SettingsEnvironmentLayer}) - which converges the keys a plugin or a format reads through a
 *       {@code jenreg.*} lookup rather than the live snapshot, and lets a cleared key stop shadowing its
 *       packaged default rather than leaving the boot-time value behind; and</li>
 *   <li>the {@link MaintenanceScheduler}'s enabled task list - so a background pass (cleanup, scan, dependents) toggled
 *       through the modules console starts or drops out on the worker's next iteration without a restart.</li>
 * </ul>
 *
 * <p>This completes the interval re-read: the {@link Settings} snapshot alone already converged pods for live keys,
 * but the environment source was seeded once at boot, so lookup-consumed keys never converged without a restart. With
 * both re-seeded here, a write-anywhere multi-node deployment converges for every runtime-tunable key.
 *
 * <p>A malformed stored value (a bad severity, duration or number written on another node) makes the live rebuild
 * throw; this pass logs it and keeps serving the last good configuration rather than propagating out of the scheduler -
 * the keep-last-good guard the {@link LiveConfig#rebuild()} writer relies on. It still records the (rejected) store
 * state as converged, so a bad value is not retried every tick; a later good write changes the stored settings again
 * and is picked up then. Settings that own a client or a thread (the advisory feed, the trackers, the audit trail, the
 * worker toggles) are deliberately not re-resolved here: they are seeded once at boot and stay honestly restart-bound,
 * badged {@code restart} on the settings surface.
 */
public final class SettingsRefresh {

    private static final Logger LOGGER = LoggerFactory.getLogger(SettingsRefresh.class);

    private final Settings settings;
    private final LiveConfig live;
    private final ConfigurableEnvironment environment;
    private final MaintenanceScheduler maintenance;

    /** The stored settings this pass last converged into {@link LiveConfig} and the environment, so an unchanged store
     *  does no work. Seeded from the boot state, which {@link SettingsEnvironmentLayer} and the {@link LiveConfig}
     *  constructor have already applied, so the first tick over an unchanged store is a no-op. */
    private volatile Map<String, String> converged;
    private volatile String epochSeen = "";

    public SettingsRefresh(Settings settings, LiveConfig live, ConfigurableEnvironment environment,
                           MaintenanceScheduler maintenance) {
        this.settings = settings;
        this.live = live;
        this.environment = environment;
        this.maintenance = maintenance;
        this.converged = settings.overrides();
    }

    @Scheduled(fixedRateString = "${jenreg.settings-refresh-millis:30000}")
    public void refresh() {
        try {
            // One point read decides whether anything changed: a store this node or another wrote to bumps the epoch,
            // and a refresh that sees the token it saw last time lists and re-reads nothing. An empty token is a
            // store nothing has written to since the epoch existed, which is re-read as before.
            String epoch = settings.epoch();
            if (!epoch.isEmpty() && epoch.equals(epochSeen)) {
                return;
            }
            epochSeen = epoch;
            settings.refresh();
        } catch (IOException e) {
            LOGGER.warn("Could not read the settings epoch; keeping the last known values", e);
            return;
        } catch (RuntimeException e) {
            LOGGER.warn("Could not re-read the stored runtime settings; keeping the last known values", e);
            return;
        }
        Map<String, String> current = settings.overrides();
        if (current.equals(converged)) {
            return;   // the stored version is unchanged since the last convergence - nothing to re-seed
        }
        // Re-seed the environment first, so the live rebuild's file/env fallback sees a cleared key revert to its
        // packaged default rather than keep reading a stale stored value the boot-time source still carried.
        SettingsEnvironmentLayer.refresh(environment, current);
        try {
            live.rebuild();
        } catch (RuntimeException e) {
            LOGGER.warn("A stored setting did not parse on refresh; keeping the last good live configuration", e);
        }
        // Re-resolve the maintenance scheduler's task list, so a background pass (cleanup, scan, dependents) toggled
        // through the modules console starts or drops out on the worker's next iteration without a restart. This is
        // the CONTAINED half of the resolve split: a provider that cannot build its task disables itself (reported
        // FAILED on the observability surface) while the other toggles in the same settings write still converge -
        // unlike boot, where the same failure fails the context. The catch below is the outer belt for the one thing
        // containment deliberately lets through, a §9 selection failure: on a running deployment that keeps the last
        // resolved list whole and logged rather than taking a serving server down on a settings edit.
        try {
            maintenance.refresh();
        } catch (RuntimeException e) {
            LOGGER.warn("A maintenance task setting did not parse on refresh; keeping the last resolved task list", e);
        }
        // Record this store state as converged even when the rebuild rejected it, so a bad value is not retried every
        // tick; a later good write changes the stored settings again and is picked up then.
        converged = current;
    }
}
