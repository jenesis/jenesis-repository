package build.jenesis.repository.application;

import module java.base;

import build.jenesis.repository.store.Durations;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.MaintenanceObservability;
import build.jenesis.repository.server.kernel.MaintenanceScheduler;
import build.jenesis.repository.server.kernel.PinnedSettings;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.RepositoryProperties;
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
 * The background workers: the download and key-usage trackers, the {@link MaintenanceScheduler} and the
 * {@link SettingsRefresh} convergence pass. A read-only deployment runs none that writes.
 */
@Configuration(proxyBeanMethods = false)
public class WorkersConfig {

    @Bean(initMethod = "start", destroyMethod = "close")
    public DownloadTracker downloadTracker(RepositoryProperties properties, Repositories repositories,
                                           Environment environment) {
        // NONE when absent or read-only: nothing records, and retention's not-downloaded-for judges by publish age.
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
        if (properties.isReadOnly()) {
            return KeyUsageTracker.NONE;
        }
        return KeyUsageTrackerProvider.resolve(authorization,
                Features.namespaced(environment::getProperty));
    }

    /** The rebuild driver, declared off: the maintenance scheduler runs the rebuild walk per repository under its
     *  lease, with the listing repair riding it as the {@code listing-rebuild} consumer. */
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
        // signalSnapshots is unread: several passes resolve signal sources, which need the snapshot space bound.
        // Each pass reads its enablement and cadence through the effective chain, so a change applies on its next run.
        UnaryOperator<String> config = pinnedSettings.effective(settings, environment);
        // The same chain resolved for a pass's own tenant, so a tenant-overridable setting takes effect in its sweep.
        BiFunction<String, String, String> tenantConfig =
                (tenant, key) -> pinnedSettings.effective(settings, environment, tenant).apply(key);
        // The task list is re-resolved on each SettingsRefresh tick, so a toggled pass converges without a restart;
        // read-only resolves it empty. At boot a provider that cannot build its task fails the context, since nothing
        // would re-resolve a failure caused by the environment; on a tick the server is serving, so one bad provider
        // is contained and the others still converge.
        MaintenanceScheduler scheduler = new MaintenanceScheduler(repositories, store,
                properties.isReadOnly() ? List.of() : MaintenanceTaskProvider.resolve(config),
                () -> properties.isReadOnly() ? MaintenanceTaskProvider.Contained.of(List.of())
                        : MaintenanceTaskProvider.resolveContained(config), config,
                tenantConfig,
                (tenant, repository, key) -> pinnedSettings.effective(settings, environment, tenant, repository)
                        .apply(key),
                leaseTtl(properties.getCleanupLease()), meterRegistry);
        return scheduler;
    }

    /** Each enabled maintenance pass's last run and outcome, reported from the scheduler this context runs. */
    @Bean
    public MaintenanceObservability maintenanceObservability(MaintenanceScheduler maintenanceScheduler) {
        return new MaintenanceObservability(maintenanceScheduler);
    }

    /**
     * The maintenance lease's ttl, refused when unparseable rather than defaulted like a cadence: a bad ttl would
     * leave the deployment believing it holds an exclusion it does not. {@code LeaseGuard} refuses the degenerate
     * values.
     */
    private static Duration leaseTtl(String configured) {
        try {
            return Durations.parse(configured);
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("jenrepo.cleanup-lease=" + configured + " is not a duration "
                    + "(e.g. PT10M or 10m). It is how long one node holds the single-writer background-maintenance "
                    + "lease, so it is refused rather than defaulted: a deployment must not believe it has an exclusion "
                    + "it does not have.", malformed);
        }
    }

    @Bean
    public SettingsRefresh settingsRefresh(Settings settings, LiveConfig liveConfig,
                                           ConfigurableEnvironment environment,
                                           MaintenanceScheduler maintenanceScheduler) {
        // When the stored settings changed on another node, re-seeds the live gate and the environment's settings
        // source and re-resolves the task list, so every runtime-tunable key converges without a restart.
        return new SettingsRefresh(settings, liveConfig, environment, maintenanceScheduler);
    }
}
