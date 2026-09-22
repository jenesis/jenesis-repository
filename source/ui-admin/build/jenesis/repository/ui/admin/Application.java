package build.jenesis.repository.ui.admin;

import build.jenesis.repository.ui.identity.ConsoleIdentityConfig;
import build.jenesis.repository.ui.ConsoleScreensConfig;
import org.springframework.context.annotation.Import;
import build.jenesis.repository.ui.ConsoleModulesConfig;
import org.springframework.boot.autoconfigure.SpringBootApplication;
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

}
