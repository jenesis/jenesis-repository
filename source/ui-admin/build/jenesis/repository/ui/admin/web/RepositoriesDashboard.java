package build.jenesis.repository.ui.admin.web;

import module java.base;

import build.jenesis.repository.ui.DashboardContributor;
import build.jenesis.repository.ui.DashboardPanel;
import build.jenesis.repository.ui.store.RepositoryAdmin;
import org.springframework.stereotype.Component;

/** The dashboard's repositories panel: how many the tenant has, and the way to the first where it has none - one
 *  listing of the tenant's names, the read the repositories list makes. */
@Component
public class RepositoriesDashboard implements DashboardContributor {

    private final RepositoryAdmin repositories;

    public RepositoriesDashboard(RepositoryAdmin repositories) {
        this.repositories = repositories;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public List<DashboardPanel> panels(Viewer viewer) {
        int held = repositories.repositories().size();
        return List.of(new DashboardPanel("Repositories", "/ui/repositories", Integer.toString(held),
                held == 1 ? "repository" : "repositories", DashboardPanel.Tone.NEUTRAL, held > 0 ? List.of()
                        : List.of(new DashboardPanel.Line("None yet", "New repository", "/ui/new/repository"))));
    }
}
