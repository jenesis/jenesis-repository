package build.jenesis.repository.ui.admin.config;

import module java.base;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.templatemode.TemplateMode;

/**
 * Resolves the shared console shell from the base module, which ships it under {@code META-INF/templates/} and
 * {@code META-INF/resources/}. The resolver answers only its namespaces, by resolvable patterns rather than order,
 * because the base module carries pages under names this console also uses ({@code browse}, {@code login}). Static
 * files need no wiring: {@code classpath:/META-INF/resources/} leads Spring Boot's static-resource chain.
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
        // base is the shared layout; console/* are the base module's screens this console serves unchanged.
        resolver.setResolvablePatterns(Set.of("base", "base/*", "console/*"));
        resolver.setCheckExistence(true);
        resolver.setOrder(1);
        return resolver;
    }
}
