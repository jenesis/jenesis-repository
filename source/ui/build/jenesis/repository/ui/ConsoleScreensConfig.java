package build.jenesis.repository.ui;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The screens this console owns, as one importable unit.
 *
 * <p>They are registered by name rather than left to a component scan because a console composed from another
 * package would otherwise pick them up only by accident of where its own scan happens to reach - which is how the
 * module container worked until it silently stopped, and the same trap applies to every screen that lives here and
 * is served there. Any composition that serves this console imports this, and gets the same screens at the same
 * routes as every other.
 */
@Configuration(proxyBeanMethods = false)
@Import({SpiCatalogScreenController.class, PostureScreenController.class, ObservabilityScreenController.class})
public class ConsoleScreensConfig {
}
