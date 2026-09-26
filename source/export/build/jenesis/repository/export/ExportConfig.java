package build.jenesis.repository.export;

import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.store.Features;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Wires the export endpoints over the server's routing and its {@code jenreg.*} settings. */
@Configuration(proxyBeanMethods = false)
public class ExportConfig {

    @Bean
    public Exports exports(RepositoryRouting routing, Environment environment) {
        return new Exports(routing, key -> environment.getProperty(Features.key(key)));
    }

    @Bean
    public ExportController exportController(Exports exports, RepositoryRouting routing) {
        return new ExportController(exports, routing);
    }
}
