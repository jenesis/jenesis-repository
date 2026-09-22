package build.jenesis.repository.index.web;

import build.jenesis.repository.server.kernel.Repositories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the published-index web adapter into the repository server: the {@link IndexController} over the
 * framework-free published index, resolved per tenant-and-repository through {@link Repositories}.
 * Imported through {@code ServerModuleProvider} discovery (see {@link IndexWebModule}), never named by the server -
 * so with this module absent the server carries no index endpoints.
 */
@Configuration(proxyBeanMethods = false)
public class IndexWebConfig {

    @Bean
    public IndexController indexController(Repositories repositories) {
        return new IndexController(repositories);
    }
}
