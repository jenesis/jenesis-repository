package build.jenesis.repository.export.web;

import build.jenesis.repository.ui.ConsoleTemplates;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;

/** The export screen's controller and its own template namespace. */
@Configuration(proxyBeanMethods = false)
@Import(ExportScreenController.class)
public class ExportConsoleConfig {

    /** This module's own template namespace, scoped so it can never answer for another module's screen. */
    @Bean
    public SpringResourceTemplateResolver exportTemplateResolver(ApplicationContext context) {
        return ConsoleTemplates.resolver(context, ExportConsoleModule.NAME);
    }
}
