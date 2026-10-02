package build.jenesis.repository.ui.admin.web;

import module java.base;

import build.jenesis.repository.ui.DashboardContributor;
import build.jenesis.repository.ui.DashboardPanel;
import build.jenesis.repository.ui.store.CacheService;
import org.springframework.stereotype.Component;

/** The dashboard's build-cache panel: how many projects the tenant has, and the way to the first where it has none -
 *  one listing of the tenant's project names. */
@Component
public class BuildCacheDashboard implements DashboardContributor {

    private final CacheService projects;

    public BuildCacheDashboard(CacheService projects) {
        this.projects = projects;
    }

    @Override
    public int order() {
        return 11;
    }

    @Override
    public List<DashboardPanel> panels(Viewer viewer) {
        int held = projects.projectCount();
        return List.of(new DashboardPanel("Build cache", "/ui/projects", Integer.toString(held),
                held == 1 ? "project" : "projects", DashboardPanel.Tone.NEUTRAL, held > 0 ? List.of()
                        : List.of(new DashboardPanel.Line("None yet", "New project", "/ui/new/project"))));
    }
}
