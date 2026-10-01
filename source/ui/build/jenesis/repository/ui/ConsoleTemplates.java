package build.jenesis.repository.ui;

import module java.base;

import org.springframework.context.ApplicationContext;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.templatemode.TemplateMode;

/**
 * The template resolver a console module contributes for its own screens, shipped under
 * {@code META-INF/templates/<namespace>/} (a location the module system derives no package from). It answers
 * {@code <namespace>/*} and nothing else: an ordered resolver answering every name would let one module's
 * {@code browse.html} serve another's screen.
 */
public final class ConsoleTemplates {

    private ConsoleTemplates() {
        throw new UnsupportedOperationException();
    }

    /**
     * A resolver for one module's screens, scoped to {@code namespace}.
     *
     * @param context   the application context, which the resolver reads its resources through.
     * @param namespace the folder under {@code META-INF/templates/} this module's screens live in, and the only
     *                  name space this resolver will answer for.
     */
    public static SpringResourceTemplateResolver resolver(ApplicationContext context, String namespace) {
        Objects.requireNonNull(namespace, "namespace");
        SpringResourceTemplateResolver resolver = new SpringResourceTemplateResolver();
        resolver.setApplicationContext(context);
        resolver.setPrefix("classpath:/META-INF/templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setResolvablePatterns(Set.of(namespace + "/*"));
        // A claimed name this resolver does not carry falls through to the next resolver.
        resolver.setCheckExistence(true);
        resolver.setOrder(1);
        return resolver;
    }
}
