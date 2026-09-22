package build.jenesis.repository.walk.web;

import module java.base;
import module spring.context;

import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;

import build.jenesis.repository.ui.ConsoleTemplates;

/** The screen's wiring: its controller, and its own template namespace so it can never answer for another module's. */
@Configuration(proxyBeanMethods = false)
@Import(WalksController.class)
public class WalksConfig {

    @Bean
    public SpringResourceTemplateResolver walksTemplateResolver(ApplicationContext context) {
        return ConsoleTemplates.resolver(context, WalksConsoleModule.NAME);
    }
}
