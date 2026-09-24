package build.jenesis.repository.compliance.web;

import build.jenesis.repository.ui.ConsoleTemplates;
import org.springframework.context.ApplicationContext;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.ui.store.ConsoleActor;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;

/** The screening feature's console beans: its screens, and the templates that travel with the module. */
@Configuration(proxyBeanMethods = false)
public class ComplianceConsoleConfig {

    /** The template namespace this module's resolver answers for, so its pages cannot shadow another module's. */
    public static final String QUALIFIER = "compliance";

    @Bean
    public ComplianceScreenController complianceScreenController(ComplianceReview compliance) {
        return new ComplianceScreenController(compliance);
    }

    @Bean
    public SpringResourceTemplateResolver complianceTemplateResolver(ApplicationContext context) {
        return ConsoleTemplates.resolver(context, QUALIFIER);
    }
    /**
     * This feature's console read service over its own ledgers.
     *
     * <p>It was declared by the console, which had to know the type to build it. The console no longer does: the
     * screens that read it are contributed from here, so the service is too, and a deployment without this module
     * has neither.
     */
    @Bean
    public ComplianceReview complianceReview(ArtifactStore repositoryStore, CurrentTenant currentTenant,
                                             ObservationRegistry observations, AuditTrail audit, ConsoleActor actor) {
        return new ComplianceReview(repositoryStore, currentTenant, observations, audit, actor);
    }

}
