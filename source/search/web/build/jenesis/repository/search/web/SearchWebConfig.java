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

    /** The one search, holding the index provider and the readers it caches; closed with the context. */
    @Bean
    public RepositorySearch repositorySearch() {
        return new RepositorySearch();
    }

    @Bean
    public BrowseController browseController(Repositories repositories, RepositoryRouting routing,
                                             RepositorySearch repositorySearch) {
        return new BrowseController(repositories, routing, repositorySearch);
    }
}
