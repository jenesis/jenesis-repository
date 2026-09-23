package build.jenesis.repository.application;

import module java.base;

import build.jenesis.repository.store.Durations;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.MaintenanceObservability;
import build.jenesis.repository.server.kernel.MaintenanceScheduler;
import build.jenesis.repository.server.kernel.PinnedSettings;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.RepositoryProperties;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.server.kernel.SettingsRefresh;
import build.jenesis.repository.server.RebuildScheduler;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.KeyUsageTracker;
import build.jenesis.repository.server.spi.KeyUsageTrackerProvider;
import build.jenesis.repository.inventory.DownloadTracker;
import build.jenesis.repository.inventory.DownloadTrackerProvider;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;
import io.micrometer.core.instrument.MeterRegistry;
import build.jenesis.repository.compliance.SignalContext;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

/**
 * The background-worker wiring split out of {@link RepositoryConfig}: the discovered download- and
 * key-usage trackers (off in read-only mode), the {@link MaintenanceScheduler} whose task list is re-resolved live,
 * and the {@link SettingsRefresh} convergence pass that re-seeds the live gate, the stored-settings source and the
 * scheduler on each tick. Every bean is copied verbatim from the former monolith; the split is behaviour-preserving.
 */
@Configuration(proxyBeanMethods = false)
public class WorkersConfig {

    @Bean(initMethod = "start", destroyMethod = "close")
    public DownloadTracker downloadTracker(RepositoryProperties properties, Repositories repositories,
                                           Environment environment) {
        // Download tracking is a discovered plugin (the downloads module); NONE when absent - nothing records,
        // the worker reports as off, and retention's not-downloaded-for judges by publish age. A read-only
        // deployment records nothing either, since the last-downloaded marker is a store write.
        if (properties.isReadOnly()) {
            return DownloadTracker.NONE;
        }
        return DownloadTrackerProvider.resolve(
                (tenant, repo) -> new StoreRepositoryInventory(repositories.store(tenant, repo)),
                Features.namespaced(environment::getProperty));
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public KeyUsageTracker keyUsageTracker(RepositoryProperties properties, Authorization authorization,
                                           Environment environment) {
        // Usage tracking is a discovered plugin (the usage module); NONE when absent - nothing records and
        // the worker reports as off. A read-only deployment records nothing either, since the usage counter is a
        // store write.
        if (properties.isReadOnly()) {
            return KeyUsageTracker.NONE;
        }
        return KeyUsageTrackerProvider.resolve(authorization,
                Features.namespaced(environment::getProperty));
    }

    /** The core's scheduled rebuild driver stands down here: this edition's maintenance scheduler drives its
     *  two jobs - the rebuild walk and the daily stored-listing repair - as the {@code rebuild} and
     *  {@code listing-rebuild} tasks, per repository and under its lease, so the driver is declared off rather
     *  than running a second, fixed-tenant pass beside them. The first task is on whenever a walk resolves; the
     *  second is opt-in in code and switched on by the image ({@code Server.DEFAULTS}), which is what keeps this
     *  edition repairing what the one repairs. */
    @Bean(initMethod = "start", destroyMethod = "close")
    public RebuildScheduler rebuildScheduler(ArtifactStore store) {
        return new RebuildScheduler(store, store, key -> RebuildScheduler.INTERVAL.equals(key) ? "off" : null,
                Optional.empty(), List.of());
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public MaintenanceScheduler maintenanceScheduler(RepositoryProperties properties, Repositories repositories,
                                                     ArtifactStore store, Settings settings, Environment environment,
                                                     PinnedSettings pinnedSettings, MeterRegistry meterRegistry,
                                                     SignalContext.Deployment signalSnapshots) {
        // The signal-snapshot binding is a parameter, not an ordering annotation: several scheduled passes (scan,
        // kev-enforce, re-analysis, health-scan) resolve the discovered signal sources, and a source created before
        // the deployment bound its root store would have no durable snapshot space to mirror into.
        // Background passes are discovered plugins (cleanup, scan, ...); each reads its own enablement and interval
        // from the effective config (an operator's pin over the runtime settings over the deployment properties), so
        // a settings change to a pass's policy applies on its next run without a restart.
        UnaryOperator<String> config = pinnedSettings.effective(settings, environment);
        // The same pin > override > default chain, but resolved for a running pass's own tenant, so a tenant-overridable
        // setting (a per-tenant webhook endpoint, gate policy or forward target) actually takes effect in the sweep
        // rather than being silently read deployment-wide. Settings.getOrDefault(tenant, ...) resolves a global-only key
        // deployment-wide, so this agrees with the global `config` on every deployment knob - only tenant-overridable
        // keys differ, which is the point.
        BiFunction<String, String, String> tenantConfig =
                (tenant, key) -> pinnedSettings.effective(settings, environment, tenant).apply(key);
        // The task list is re-resolved on each SettingsRefresh convergence tick (a Supplier, not a fixed list), so a
        // pass toggled through the modules console converges here without a restart - each provider re-reads its own
        // enablement through the same live (deployment-global) config lookup.
        // A read-only deployment runs no background pass that mutates the store (GC / reclamation, the sizes /
        // dependents sweeps, scheduled scan & cleanup, the forwarding watermark, continuous re-analysis): the task
        // list resolves empty, so nothing is scheduled and nothing writes.
        // Boot and refresh resolve through two DIFFERENT entry points, and the difference is the whole point: here in
        // the @Bean method a provider that cannot build its task fails this context (resolve), because only a failure
        // caused by a stored setting would ever be re-resolved - one caused by an env var, a config file or a
        // transient condition has nothing to trigger the convergence tick and would leave the deployment permanently
        // short a pass. On the tick itself the server is already serving, so one bad provider is contained
        // (resolveContained) and the other toggles in the same settings write still converge.
        MaintenanceScheduler scheduler = new MaintenanceScheduler(repositories, store,
                properties.isReadOnly() ? List.of() : MaintenanceTaskProvider.resolve(config),
                () -> properties.isReadOnly() ? List.of() : MaintenanceTaskProvider.resolveContained(config), config,
                tenantConfig, leaseTtl(properties.getCleanupLease()), meterRegistry);
        // Install the live scheduler as the source the discovered MaintenanceObservability adapter reports each enabled
        // task's last-run / status from - the same install/installed static-holder seam the spool store uses, so the
        // observation report degrades gracefully to nothing when no scheduler is wired.
        MaintenanceObservability.install(scheduler);
        return scheduler;
    }

    /**
     * The single-writer maintenance lease's ttl. Deliberately fail-fast and named (&sect;9), unlike a task's cadence
     * dial: a cadence that will not parse degrades to the announced default because the pass still has a correct thing
     * to do, but a lease ttl that will not parse - or that is zero or negative - would leave the deployment believing
     * it holds an exclusion it does not have. {@code LeaseGuard} refuses the degenerate values; this refuses the
     * unparseable ones, both naming {@code cleanup-lease} so the operator sees which dial to fix.
     */
    private static Duration leaseTtl(String configured) {
        try {
            return Durations.parse(configured);
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("jenreg.cleanup-lease=" + configured + " is not a duration "
                    + "(e.g. PT10M or 10m). It is how long one node holds the single-writer background-maintenance "
                    + "lease, so it is refused rather than defaulted: a deployment must not believe it has an exclusion "
                    + "it does not have.", malformed);
        }
    }

    @Bean
    public SettingsRefresh settingsRefresh(Settings settings, LiveConfig liveConfig,
                                           ConfigurableEnvironment environment,
                                           MaintenanceScheduler maintenanceScheduler) {
        // The scheduled convergence pass: on the refresh interval it re-reads the stored settings and, when they
        // changed on another node, re-seeds both the live gate (LiveConfig) and the environment's stored-settings
        // source, and re-resolves the maintenance scheduler's task list - so every runtime-tunable key converges across
        // a multi-node deployment without a restart, not just the LiveConfig ones, and a background pass toggled through
        // the modules console starts or stops on the worker's next iteration; the restart-bound keys (feeds, trackers,
        // audit, worker toggles) stay badged as such.
        return new SettingsRefresh(settings, liveConfig, environment, maintenanceScheduler);
    }
}
