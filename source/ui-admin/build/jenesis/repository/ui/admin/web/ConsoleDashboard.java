package build.jenesis.repository.ui.admin.web;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.ui.DashboardContributor;
import build.jenesis.repository.ui.DashboardPanel;
import build.jenesis.repository.ui.admin.config.RepositoryStoreConfig;
import build.jenesis.repository.ui.store.CacheService;
import build.jenesis.repository.ui.store.RepositoryAdmin;
import build.jenesis.repository.ui.store.SettingsAdmin;
import build.jenesis.repository.ui.store.TenantLimits;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The console's own dashboard panels, the central figures and the one task the console itself raises: the tenant's
 * repositories and build-cache projects, the store its content is kept in with the space left, and - for a super-admin
 * - the unsafe settings the security posture names. Each opens its menu entry, and each is a listing of names or a
 * point read, the reads the repositories list, the limits screen and the header's posture badge already make.
 */
@Component
public class ConsoleDashboard implements DashboardContributor {

    private final RepositoryAdmin repositories;
    private final CacheService projects;
    private final TenantLimits limits;
    private final ArtifactStore store;
    private final SettingsAdmin settings;
    private final Environment environment;
    private final Format format;

    public ConsoleDashboard(RepositoryAdmin repositories, CacheService projects, TenantLimits limits,
                            ArtifactStore repositoryStore, SettingsAdmin settings, Environment environment,
                            Format format) {
        this.repositories = repositories;
        this.projects = projects;
        this.limits = limits;
        this.store = repositoryStore;
        this.settings = settings;
        this.environment = environment;
        this.format = format;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public List<DashboardPanel> panels(Viewer viewer) throws IOException {
        List<DashboardPanel> panels = new ArrayList<>();
        int held = repositories.repositories().size();
        int cached = projects.projectCount();
        List<DashboardPanel.Line> lines = new ArrayList<>();
        lines.add(new DashboardPanel.Line("Build-cache projects", Integer.toString(cached), "/ui/projects"));
        if (held == 0) {
            lines.addFirst(new DashboardPanel.Line("None yet", "New repository", "/ui/new/repository"));
        }
        panels.add(new DashboardPanel("Repositories", "/ui/repositories", Integer.toString(held),
                held == 1 ? "repository" : "repositories", DashboardPanel.Tone.NEUTRAL, lines));
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
        String backend = environment.getProperty("jenrepo.store", RepositoryStoreConfig.DEFAULT_BACKEND);
        List<DashboardPanel.Line> lines = new ArrayList<>();
        lines.add(new DashboardPanel.Line("Store", backend, null));
        TenantLimits.QuotaView quota = limits.quota();
        if (quota.maxBytes() > 0) {
            lines.add(new DashboardPanel.Line("Quota used",
                    format.bytes(quota.usedBytes()) + " of " + format.bytes(quota.maxBytes()), "/ui/limits"));
        }
        Optional<ArtifactStore.Capacity> capacity = viewer.superadmin() ? store.capacity() : Optional.empty();
        if (capacity.isPresent()) {
            long free = capacity.get().usable();
            long total = capacity.get().total();
            long freePercent = total == 0 ? 0 : Math.round(100.0 * free / total);
            return new DashboardPanel("Storage", "/ui/metrics", format.bytes(free),
                    "free of " + format.bytes(total) + " (" + freePercent + "%)",
                    freePercent < 10 ? DashboardPanel.Tone.ATTENTION : DashboardPanel.Tone.NEUTRAL, lines);
        }
        if (quota.maxBytes() > 0) {
            lines.removeLast();
            return new DashboardPanel("Storage", "/ui/limits", format.bytes(quota.usedBytes()),
                    "stored of a " + format.bytes(quota.maxBytes()) + " quota",
                    quota.usedBytes() >= quota.maxBytes() ? DashboardPanel.Tone.ATTENTION
                            : DashboardPanel.Tone.NEUTRAL, lines);
        }
        return new DashboardPanel("Storage", "/ui/limits", "", "This store does not report its space",
                DashboardPanel.Tone.NEUTRAL, lines);
    }
}
