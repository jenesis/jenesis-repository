package build.jenesis.repository.ui;

import module java.base;

import org.springframework.context.ApplicationContext;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.templatemode.TemplateMode;

/**
 * The template resolver a console module contributes for its own screens.
 *
 * <p>A module that adds screens ships them under {@code META-INF/templates/<namespace>/} - a location the module
 * system derives no package from, so requiring the module splits nothing - and needs a resolver that will answer
 * for that namespace and no other. Every such module wrote the same eight lines, and the important one is easy to
 * leave out: <b>the resolvable patterns</b>. Without them the resolver answers for every name, and Thymeleaf
 * consults explicitly ordered resolvers ahead of the unordered default - so one module's {@code browse.html} can
 * silently serve another's browse screen, which is a page that renders perfectly and is the wrong page.
 *
 * <p>So the namespace is the argument and the scoping is not optional. A module passes the folder its templates
 * live in; it gets a resolver that answers {@code <namespace>/*} and nothing else.
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
        // Existence is checked so that a name this resolver claims but does not carry falls through to the next
        // resolver rather than becoming an error, which is what lets several scoped resolvers coexist at all.
        resolver.setCheckExistence(true);
        resolver.setOrder(1);
        return resolver;
    }
}
