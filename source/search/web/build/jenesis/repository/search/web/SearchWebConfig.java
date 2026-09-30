package build.jenesis.repository.search.web;

import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.search.service.RepositorySearch;
import build.jenesis.repository.server.kernel.Repositories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the browse / search / license-inventory read adapter into the repository server: the {@link BrowseController}
 * over the framework-free {@link Repositories} resolver and the one {@link RepositorySearch}, registered as an explicit
 * {@code @Bean} - Spring MVC maps the {@code @RestController} handler methods on the bean instance, so no component
 * scan crosses the module boundary. Imported through {@code ServerModuleProvider} discovery (see
 * {@link SearchWebModule}), never named by the server - so with this module absent the server carries no
 * {@code /api/browse}, {@code /api/search} or {@code /api/licenses} endpoint.
 */
@Configuration(proxyBeanMethods = false)
public class SearchWebConfig {

    @Bean
    public BrowseController browseController(Repositories repositories, RepositoryRouting routing) {
        return new BrowseController(repositories, routing, new RepositorySearch());
    }
}
