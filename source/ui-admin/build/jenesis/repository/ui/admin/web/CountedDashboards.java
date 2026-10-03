package build.jenesis.repository.ui.admin.web;

import build.jenesis.repository.ui.DashboardPanel;
import build.jenesis.repository.ui.store.CacheService;
import build.jenesis.repository.ui.store.RepositoryAdmin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The dashboard's two counted panels: the tenant's repositories, then its build-cache projects. */
@Configuration(proxyBeanMethods = false)
public class CountedDashboards {

    @Bean
    CountedDashboard repositoriesDashboard(RepositoryAdmin repositories) {
        return new CountedDashboard(10, "Repositories", "/ui/repositories", () -> repositories.repositories().size(),
                new DashboardPanel.Noun("repository", "repositories"),
                new DashboardPanel.Link("New repository", "/ui/new/repository"));
    }

    @Bean
    CountedDashboard buildCacheDashboard(CacheService projects) {
        return new CountedDashboard(11, "Build cache", "/ui/projects", projects::projectCount,
                new DashboardPanel.Noun("project", "projects"),
                new DashboardPanel.Link("New project", "/ui/new/project"));
    }
}
