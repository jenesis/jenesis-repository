package build.jenesis.repository.index.web;

import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.Repositories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the {@link IndexController} over the published index, resolved per tenant and repository through
 *  {@link Repositories}. */
@Configuration(proxyBeanMethods = false)
public class IndexWebConfig {

    @Bean
    public IndexController indexController(Repositories repositories, RepositoryRouting routing) {
        return new IndexController(repositories, routing);
    }
}
