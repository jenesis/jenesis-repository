package build.jenesis.repository.ui.admin.web;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.ui.DashboardContributor;
import build.jenesis.repository.ui.DashboardPanel;
import build.jenesis.repository.ui.admin.config.RepositoryStoreConfig;
import build.jenesis.repository.ui.store.SettingsAdmin;
import build.jenesis.repository.ui.store.TenantLimits;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The deployment's dashboard panels: the store its content is kept in with the space left, and - for a super-admin -
 * the unsafe settings the security posture names. Each opens its menu entry and is a point read, the reads the limits
 * screen and the header's posture badge already make. The repositories and the build cache have panels of their own
 * ({@link RepositoriesDashboard}, {@link BuildCacheDashboard}).
 */
@Component
public class ConsoleDashboard implements DashboardContributor {

    private final TenantLimits limits;
    private final ArtifactStore store;
    private final SettingsAdmin settings;
    private final Environment environment;
    private final Format format;
    /** Where the deployment's store keeps content, as the storage panel says it; fixed for the JVM. */
    private final String where;

    public ConsoleDashboard(TenantLimits limits,
                            ArtifactStore repositoryStore, SettingsAdmin settings, Environment environment,
                            Format format) {
        this.limits = limits;
        this.store = repositoryStore;
        this.settings = settings;
        this.environment = environment;
        this.format = format;
        this.where = ArtifactStoreProvider.where(environment.getProperty("jenrepo.store",
                RepositoryStoreConfig.DEFAULT_BACKEND));
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public List<DashboardPanel> panels(Viewer viewer) throws IOException {
        List<DashboardPanel> panels = new ArrayList<>();
        panels.add(storage(viewer));
        if (viewer.superadmin()) {
            int advisories = settings.posture(viewer.tenant(), environment::getProperty).posture().count();
            panels.add(new DashboardPanel("Security posture", "/ui/posture",
                    advisories == 0 ? "" : Integer.toString(advisories),
                    advisories == 0 ? "No unsafe setting is in force"
                            : advisories == 1 ? "unsafe setting is in force" : "unsafe settings are in force",
                    advisories == 0 ? DashboardPanel.Tone.CLEAR : DashboardPanel.Tone.ATTENTION, List.of()));
        }
        return panels;
    }

    /**
     * The store and its space: the volume's free bytes where the backend has a volume and the reader may see the
     * deployment's, else the bytes stored against the quota where one meters them. Without either the store says
     * nothing about space, and the panel says so rather than showing a zero.
     */
    private DashboardPanel storage(Viewer viewer) throws IOException {
        TenantLimits.QuotaView quota = limits.quota();
        Optional<ArtifactStore.Capacity> capacity = viewer.superadmin() ? store.capacity() : Optional.empty();
        if (capacity.isPresent()) {
            long free = capacity.get().usable();
            long total = capacity.get().total();
            long freePercent = total == 0 ? 0 : Math.round(100.0 * free / total);
            List<DashboardPanel.Line> lines = quota.maxBytes() > 0
                    ? List.of(new DashboardPanel.Line("Quota used",
                            format.bytes(quota.usedBytes()) + " of " + format.bytes(quota.maxBytes()), "/ui/limits"))
                    : List.of();
            return new DashboardPanel("Storage", "/ui/metrics", format.bytes(free),
                    "free " + where + ", " + freePercent + "% of " + format.bytes(total),
                    freePercent < 10 ? DashboardPanel.Tone.ATTENTION : DashboardPanel.Tone.NEUTRAL, lines);
        }
        if (quota.maxBytes() > 0) {
            return new DashboardPanel("Storage", "/ui/limits", format.bytes(quota.usedBytes()),
                    "stored " + where + ", of a " + format.bytes(quota.maxBytes()) + " quota",
                    quota.usedBytes() >= quota.maxBytes() ? DashboardPanel.Tone.ATTENTION
                            : DashboardPanel.Tone.NEUTRAL, List.of());
        }
        // The store says nothing about space and no quota meters it: nothing worth a panel.
        return new DashboardPanel("Storage", "/ui/limits", "", "", DashboardPanel.Tone.NEUTRAL, List.of());
    }
}
