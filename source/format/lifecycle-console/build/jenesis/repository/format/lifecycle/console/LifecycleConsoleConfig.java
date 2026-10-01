package build.jenesis.repository.format.lifecycle.console;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.format.lifecycle.web.LifecycleMarks;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.ui.ConsoleTemplates;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.ui.store.ConsoleActor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;

/**
 * The Lifecycle page's controller and its own template namespace. The page's {@link LifecycleMarks} is built here
 * over the console's own store and audit trail, so a console booted without the repository's server modules still
 * has it; in the shipped image the two are the same store and the same trail the API's instance writes through.
 */
@Configuration(proxyBeanMethods = false)
public class LifecycleConsoleConfig {

    @Bean
    public LifecycleScreenController lifecycleScreenController(ArtifactStore repositoryStore, AuditTrail auditTrail,
                                                               CurrentTenant tenant, ConsoleActor actor) {
        return new LifecycleScreenController(new LifecycleMarks(repositoryStore, auditTrail), tenant, actor);
    }

    /** This module's own template namespace, scoped so it can never answer for another module's screen. */
    @Bean
    public SpringResourceTemplateResolver lifecycleTemplateResolver(ApplicationContext context) {
        return ConsoleTemplates.resolver(context, LifecycleConsoleModule.NAME);
    }
}
