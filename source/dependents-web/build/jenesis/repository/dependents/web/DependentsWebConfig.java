package build.jenesis.repository.dependents.web;

import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.Repositories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the reverse-dependency / blast-radius query adapter into the repository server: the
 * {@link DependentsController} over the framework-free {@link Repositories} resolver and the discovered
 * {@code DependentsQueryProvider} index, registered as an explicit {@code @Bean} - Spring MVC maps the
 * {@code @RestController} handler methods on the bean instance, so no component scan crosses the module boundary.
 * Imported through {@code ServerModuleProvider} discovery (see {@link DependentsWebModule}).
 */
@Configuration(proxyBeanMethods = false)
public class DependentsWebConfig {

    @Bean
    public DependentsController dependentsController(Repositories repositories, RepositoryRouting routing) {
        return new DependentsController(repositories, routing);
    }
}
