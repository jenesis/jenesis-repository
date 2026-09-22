package build.jenesis.repository.deploy.web;

import build.jenesis.repository.ui.ConsoleTemplates;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;

/**
 * The deploy module's beans: its screen, and the resolver that finds the screen's template.
 *
 * <p>The controller is imported by name rather than component-scanned, for the reason every console screen is: a
 * console composed from another package picks up a scanned class only by accident of where its own scan reaches,
 * and the accident is silent when it stops happening.
 */
@Configuration(proxyBeanMethods = false)
@Import(DeployController.class)
public class DeployConfig {

    /** This module's own template namespace, scoped so it can never answer for another module's screen. */
    @Bean
    public SpringResourceTemplateResolver deployTemplateResolver(ApplicationContext context) {
        return ConsoleTemplates.resolver(context, DeployConsoleModule.NAME);
    }
}
