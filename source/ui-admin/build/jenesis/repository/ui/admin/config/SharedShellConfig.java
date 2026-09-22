package build.jenesis.repository.ui.admin.config;

import module java.base;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.templatemode.TemplateMode;

/**
 * Resolves the shared console shell from the base module: {@code build.jenesis.repository.ui} ships its
 * design system ({@code base.html} fragments, {@code app.css} tokens) under {@code META-INF/templates/} and
 * {@code META-INF/resources/} - locations the module system derives no package from, so requiring that module
 * splits nothing. This second resolver answers ONLY the {@code base} template namespace (resolvable patterns,
 * not ordering): the base module also carries its own console pages under names this console uses too
 * ({@code browse}, {@code login}), and an order-based fallback proved fragile - Thymeleaf consults explicitly
 * ordered resolvers ahead of the unordered default, which let the base module's {@code browse.html} hijack this
 * console's browse view. Pattern-scoping makes such collisions structurally impossible: {@code base} and
 * {@code base/*} resolve from the base module's jar, every other name stays with the console's own {@code templates/}.
 * The static half needs no wiring at all: {@code classpath:/META-INF/resources/} is first in Spring Boot's
 * default static-resource chain, so the shared {@code /css/app.css} serves from the base module's jar as-is.
 */
@Configuration(proxyBeanMethods = false)
public class SharedShellConfig {

    @Bean
    public SpringResourceTemplateResolver sharedShellTemplateResolver(ApplicationContext context) {
        SpringResourceTemplateResolver resolver = new SpringResourceTemplateResolver();
        resolver.setApplicationContext(context);
        resolver.setPrefix("classpath:/META-INF/templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        // base is the shared layout; console/* are the screens the base module owns and this console serves
        // unchanged. They are namespaced rather than listed one by one, and rather than sharing the root, because
        // this console has templates of its own by the same names - a screen resolved from the wrong root renders
        // the wrong page with no error at all.
        resolver.setResolvablePatterns(Set.of("base", "base/*", "console/*"));
        resolver.setCheckExistence(true);
        resolver.setOrder(1);
        return resolver;
    }
}
