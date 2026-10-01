package build.jenesis.repository.ui;

import module java.base;

/**
 * Marks a console screen: a {@code @Controller} under {@code /ui} that answers with a view. The console's controller
 * advice (navigation, shell attributes, error pages) selects by it, since the console and the repository share one
 * Spring context and an unselective advice would answer data-plane failures with HTML. The build's inspection holds
 * every product {@code @Controller} to carrying it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ConsoleScreen {
}
