package build.jenesis.repository.bundle;

import build.jenesis.repository.server.RepositoryApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Boots the repository AND the console as one application, off the all-in-one module path.
 *
 * <p>There used to be two launchers here, selected by {@code MAINCLASS}: this one for the server and a
 * {@code Console} beside it, each with its own config file and its own port. One image that has to be told which
 * half to be is an indirection nobody wants - so there is now one entry point, one config file
 * ({@code allinone.properties}, named explicitly because two modules on this path carry a root
 * {@code application.properties}) and one port.
 *
 * <p><b>How the two halves compose.</b> The repository needs no scanning: {@link RepositoryApplication} is a bare
 * {@code @SpringBootConfiguration @EnableAutoConfiguration} launcher carrying no beans of its own, so its
 * auto-configurations are picked up by any such class in the context - this one. The console does need scanning,
 * and its own {@code @SpringBootApplication} entry point is excluded so its auto-configuration is not re-triggered.
 * Their security chains compose rather than collide: the console's is named, ordered and matched over
 * {@code ConsoleUrlSpace}, and the repository's is the unmatched fall-through, because its space carries arbitrary
 * artifact coordinates and cannot be enumerated.
 *
 * <p>Every capability on the module path runs until configured off - {@code jenreg.<feature>=false} degrades an
 * implementation exactly like a missing module, {@code jenreg.<spi>=<feature>} selects among exclusive ones - so
 * the image this launcher fronts is trimmed with {@code docker run -e}, never rebuilt.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ConfigurationPropertiesScan(basePackages = "build.jenesis.repository.ui")
@ComponentScan(basePackages = "build.jenesis.repository.ui",
        // The console's own entry point, which this class replaces. A full class name, and it must track the
        // package: the combined app once left these patterns naming a package that no longer existed, matched
        // nothing, and scanned both @SpringBootApplication classes back in.
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "build\\.jenesis\\.repository\\.ui\\.Application"))
public class AllInOne {

    private AllInOne() {
    }

    public static void main(String[] args) {
        new SpringApplicationBuilder(AllInOne.class)
                .properties("spring.config.name=allinone")
                .run(args);
    }

    /**
     * Boot the all-in-one server on the given port ({@code 0} picks an ephemeral one) and return a handle exposing
     * the bound port and closing the context, so a test can drive the exact composition the image runs over HTTP.
     * The port rides as an argument, not a property: {@code allinone.properties} pins {@code server.port=${PORT:8080}}
     * and config files outrank default properties, so a property-passed {@code 0} would silently bind 8080.
     */
    public static Running start(int port) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(AllInOne.class)
                .properties("spring.config.name=allinone")
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
