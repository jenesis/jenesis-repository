package build.jenesis.repository.ui;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The screens this console owns, as one importable unit, registered by name rather than by a component scan whose
 * reach depends on where the composing console lives. Every composition serving the console imports this and gets the
 * same screens at the same routes, the sign-in page and the {@code /no-access} screen every access check leads to
 * among them. {@link DevConsoleSecurity} and {@link DevSources} ride here too, inert outside the {@code dev}
 * profile.
 */
@Configuration(proxyBeanMethods = false)
@Import({LoginController.class, NoAccessController.class, SpiCatalogScreenController.class,
        PostureScreenController.class, MetricsScreenController.class, DevConsoleSecurity.class,
        DevSources.class})
public class ConsoleScreensConfig {
}
