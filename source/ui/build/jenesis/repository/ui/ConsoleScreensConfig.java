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
 *
 * <p>{@link DevConsoleSecurity} rides here for the same reason and is inert outside the {@code dev} profile. It is
 * the one development sign-in chain, and it is here rather than beside each console because the two that used to
 * exist had drifted: one pointed form login at a page carrying no credential form, so that console could not be
 * signed into at all.
 *
 * <p>The sign-in page is one of them, and that is worth stating because it is the screen a deployment cannot do
 * without: a composition that imported the others and not this one answered 404 on {@code /login} while its own
 * deny-by-default chain redirected every other route to it. There is no console without a way into it.
 */
@Configuration(proxyBeanMethods = false)
@Import({LoginController.class, SpiCatalogScreenController.class, PostureScreenController.class,
        ObservabilityScreenController.class, DevConsoleSecurity.class})
public class ConsoleScreensConfig {
}
