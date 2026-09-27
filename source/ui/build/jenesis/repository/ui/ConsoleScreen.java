package build.jenesis.repository.ui;

import module java.base;

/**
 * Marks a console screen: a {@code @Controller} under {@code /ui} that answers with a view, beside the
 * {@code @RestController}s of the API and the repository's data plane.
 *
 * <p>The console's controller advice - the navigation and the shell's other model attributes, and the handlers that
 * render a failure as the console's error page - selects its handlers by this annotation. The console and the
 * repository run in one Spring context in the shipped image, so an advice that selected nothing would also answer for
 * the data plane: a download whose store read failed would be answered with the console's HTML error page and a
 * {@code 200}, and every artifact request would pay for the console's navigation. The build's inspection holds every
 * {@code @Controller} of the product to carrying it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ConsoleScreen {
}
