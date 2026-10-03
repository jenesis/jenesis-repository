package build.jenesis.repository.ui.admin;

import build.jenesis.repository.server.Launched;
import build.jenesis.repository.ui.identity.ConsoleIdentityConfig;
import build.jenesis.repository.ui.ConsoleScreensConfig;
import org.springframework.context.annotation.Import;
import build.jenesis.repository.ui.ConsoleModulesConfig;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;


/**
 * The admin console's composition: not a launcher (the bundle imports {@code AdminConsoleNode}), but the
 * {@code @SpringBootApplication} a {@code @SpringBootTest} and the browser fixture boot. It loads {@code ui.properties}
 * through {@link #CONFIG_NAME_PROPERTY}, since its dependencies each ship an {@code application.properties} and Spring
 * loads only one, chosen by module-path order.
 */
@Import({ConsoleModulesConfig.class, ConsoleScreensConfig.class, ConsoleIdentityConfig.class})
@SpringBootApplication
@ConfigurationPropertiesScan
public class Application {

    /**
     * The {@code spring.config.name} assignment every boot of this console applies, {@value}: the base name of its
     * configuration file ({@code ui.properties}, {@code ui-<profile>.properties}).
     */
    public static final String CONFIG_NAME_PROPERTY = "spring.config.name=ui";

    private Application() {
    }

    /**
     * Boots this console on the given port ({@code 0} for an ephemeral one) and returns a handle with the bound port,
     * so a test drives it over HTTP without Spring types. The port is a run argument because a {@code .properties()}
     * default ranks below a configuration file that pins {@code server.port}.
     */
    public static Running start(int port) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class)
                .run("--" + CONFIG_NAME_PROPERTY, "--server.port=" + port);
        int bound = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        return new Running(bound, context);
    }

    /** A booted console: the port it bound and the context to close. */
    public static final class Running extends Launched {

        private Running(int port, ConfigurableApplicationContext context) {
            super(port, context);
        }
    }
}
