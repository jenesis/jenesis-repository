package build.jenesis.repository.compliance.web;

import build.jenesis.repository.ui.ConsoleTemplates;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.compliance.ComplianceSources;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.ui.store.ConsoleActor;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
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
    public ReviewDashboard reviewDashboard(ArtifactStore repositoryStore, CurrentTenant currentTenant,
                                           ObservationRegistry observations) {
        return new ReviewDashboard(repositoryStore, currentTenant, observations);
    }

    @Bean
    public SpringResourceTemplateResolver complianceTemplateResolver(ApplicationContext context) {
        return ConsoleTemplates.resolver(context, QUALIFIER);
    }
    /**
     * This feature's console read service over its ledgers, contributed with the screens that read it.
     */
    @Bean
    public ComplianceReview complianceReview(ArtifactStore repositoryStore, CurrentTenant currentTenant,
                                             ObservationRegistry observations, AuditTrail audit, ConsoleActor actor,
                                             Environment environment, ObjectProvider<ComplianceSources> sources) {
        // The deployment's own feeds where the console runs inside it, so the panel reads what the gate screens
        // with and warms nothing twice; a console booted alone resolves its own, once.
        return new ComplianceReview(repositoryStore, currentTenant, observations, audit, actor,
                key -> environment.getProperty(Features.key(key)), sources.getIfAvailable());
    }

}
