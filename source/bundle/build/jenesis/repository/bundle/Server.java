package build.jenesis.repository.bundle;

import build.jenesis.repository.cache.server.CacheNode;
import build.jenesis.repository.server.RepositoryApplication;
import build.jenesis.repository.ui.admin.AdminConsoleNode;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

/**
 * Boots the repository, the console and the build cache as one application, off the bundle module path.
 *
 * <p>There is one entry point, one config file ({@code bundle.properties}, named explicitly because two modules on
 * this path carry a root {@code application.properties}) and one port.
 *
 * <p><b>How the halves compose.</b> The repository needs no scanning: {@link RepositoryApplication} is a bare
 * {@code @SpringBootConfiguration @EnableAutoConfiguration} launcher carrying no beans of its own, so its
 * auto-configurations are picked up by any such class in the context - this one. The console does need scanning,
 * and its own {@code @SpringBootApplication} entry point is excluded so its auto-configuration is not re-triggered.
 * The cache is imported the same way, its endpoint under {@code /build/<tenant>/}. Their security chains compose
 * rather than collide: the console's is named, ordered and matched over {@code ConsoleUrlSpace}, the cache's is
 * matched over its own path, and the repository's is the unmatched fall-through, because its space carries
 * arbitrary artifact coordinates and cannot be enumerated.
 *
 * <p>Every capability on the module path runs until configured off - {@code jenrepo.<feature>=false} degrades an
 * implementation exactly like a missing module, {@code jenrepo.<spi>=<feature>} selects among exclusive ones - so
 * the image this launcher fronts is trimmed with {@code docker run -e}, never rebuilt.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ConfigurationPropertiesScan(basePackages = {"build.jenesis.repository.ui",
        "build.jenesis.repository.ui.identity"})
// The console and the build cache each arrive as one importable configuration that knows its own switch, since a
// launcher cannot make its own @ComponentScan conditional. There is one console, and an edition adds to it through
// the seams it declares. Without the import the cache's endpoint is never registered and every build tool's request
// is a 404, whatever the module path carries.
@Import({AdminConsoleNode.class, CacheNode.class})
// The composition is scanned rather than imported, since it is not optional; its own launcher class is excluded so
// its auto-configuration is not triggered a second time.
@ComponentScan(basePackages = "build.jenesis.repository.application",
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "build\\.jenesis\\.repository\\.application\\.RepositoryApplication"))
public class Server {

    private Server() {
    }

    public static void main(String[] args) {
        new SpringApplicationBuilder(Server.class)
                .properties("spring.config.name=bundle")
                .run(args);
    }

    /**
     * Boot the server on the given port ({@code 0} picks an ephemeral one) and return a handle exposing
     * the bound port and closing the context, so a test can drive the exact composition the image runs over HTTP.
     * The port rides as a run argument because a {@code .properties()} default sits in Spring's lowest-precedence
     * layer, where a config file or an environment variable would win and two suites asking for an ephemeral port
     * would race for one fixed port.
     */
    public static Running start(int port) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(Server.class)
                .properties("spring.config.name=bundle")
                .run("--server.port=" + port);
        return new Running(Integer.parseInt(context.getEnvironment().getProperty("local.server.port")), context);
    }

    /** A handle on a started server: the actually bound port and an orderly shutdown, without leaking Spring types. */
    public static final class Running implements AutoCloseable {

        private final int port;
        private final ConfigurableApplicationContext context;

        private Running(int port, ConfigurableApplicationContext context) {
            this.port = port;
            this.context = context;
        }

        public int port() {
            return port;
        }

        @Override
        public void close() {
            context.close();
        }
    }
}
