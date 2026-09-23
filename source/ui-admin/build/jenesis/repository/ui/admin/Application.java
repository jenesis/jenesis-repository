package build.jenesis.repository.ui.admin;

import build.jenesis.repository.ui.identity.ConsoleIdentityConfig;
import build.jenesis.repository.ui.ConsoleScreensConfig;
import org.springframework.context.annotation.Import;
import build.jenesis.repository.ui.ConsoleModulesConfig;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;


/**
 * The admin console's composition.
 *
 * <p><b>It is not a launcher.</b> The console stopped being an image of its own when the three nodes
 * became one; the bundle imports {@code AdminConsoleNode}, and what is left here is the
 * {@code @SpringBootApplication} composition a {@code @SpringBootTest} and the browser fixture boot, plus the config
 * name they must apply.
 *
 * <p>The app loads {@code ui.properties} rather than {@code application.properties}, by naming it through
 * {@link #CONFIG_NAME_PROPERTY} - the same guard {@code RepositoryApplication} ({@code repository}),
 * {@code CacheServer} ({@code cache}) and the bundle already carry, and for the
 * same reason: this module requires {@code build.jenesis.repository.server} and {@code build.jenesis.repository.ui},
 * and each of those jars ships its own root {@code application.properties}. Spring loads exactly one
 * {@code classpath:/application.properties}, so a file of that name here would compete with them - and which one won
 * depended on module-path order.
 */
@Import({ConsoleModulesConfig.class, ConsoleScreensConfig.class, ConsoleIdentityConfig.class})
@SpringBootApplication
@ConfigurationPropertiesScan
public class Application {

    /**
     * The {@code spring.config.name} assignment this app boots under - {@value} - and therefore the base name of its
     * configuration file ({@code ui.properties}, plus {@code ui-<profile>.properties} per profile). A boot that does
     * <em>every</em> boot must apply it - a {@code @SpringBootTest}, the browser suite's console fixture, the
     * bundle - or it silently configures the console from whichever dependency's {@code application.properties}
     * happens to come first on the module path. Held as the whole assignment rather than the bare name so there is
     * one literal to read, apply and check.
     */
    public static final String CONFIG_NAME_PROPERTY = "spring.config.name=ui";

    private Application() {
    }

    /**
     * Boot this console on the given port ({@code 0} picks an ephemeral one) and return a handle carrying the bound
     * port and closing the context, so a test or an embedder drives the real console over HTTP without the Spring
     * types leaking into its own module.
     *
     * <p>The port rides as a run <em>argument</em> rather than a default property, which is not a stylistic
     * choice: a {@code .properties()} default is Spring's lowest-precedence source and a configuration file on the
     * closure pins {@code server.port}, so a {@code 0} set that way is silently ignored and the boot takes the
     * fixed port instead. {@link #CONFIG_NAME_PROPERTY} rides with it for the reason that constant gives.
     */
    public static Running start(int port) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class)
                .run("--" + CONFIG_NAME_PROPERTY, "--server.port=" + port);
        int bound = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        return new Running(bound, context);
    }

    /** A booted console: the port it bound and the context to close. */
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
