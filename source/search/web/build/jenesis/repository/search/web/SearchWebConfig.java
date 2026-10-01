package build.jenesis.repository.search.web;

import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.search.service.RepositorySearch;
import build.jenesis.repository.server.kernel.Repositories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the browse, search and licence-inventory adapter into the server: {@link BrowseController} over
 * {@link Repositories} and the one {@link RepositorySearch}, an explicit {@code @Bean} so no component scan crosses the
 * module boundary. Imported through {@code ServerModuleProvider} discovery ({@link SearchWebModule}); without this
 * module there is no {@code /api/browse}, {@code /api/search} or {@code /api/licenses}.
 */
@Configuration(proxyBeanMethods = false)
public class SearchWebConfig {

    @Bean
    public BrowseController browseController(Repositories repositories, RepositoryRouting routing) {
        return new BrowseController(repositories, routing, new RepositorySearch());
    }
}
