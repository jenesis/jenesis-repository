package build.jenesis.repository.dependents.web;

import build.jenesis.repository.ui.ConsoleTemplates;
import org.springframework.context.ApplicationContext;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.ui.CurrentTenant;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** This feature's console beans: its screen, and the templates that travel with the module. */
@Configuration(proxyBeanMethods = false)
public class DependentsConsoleConfig {

    /** The template namespace this module's resolver answers for, so its pages cannot shadow another module's. */
    public static final String QUALIFIER = "dependents";

    @Bean
    public DependentsScreenController dependentsScreenController(DependentsReview dependentsReview) {
        return new DependentsScreenController(dependentsReview);
    }

    @Bean
    public SpringResourceTemplateResolver dependentsTemplateResolver(ApplicationContext context) {
        return ConsoleTemplates.resolver(context, QUALIFIER);
    }
    /** This module's own console read service over its index - see {@link DependentsReview}. */
    @Bean
    public DependentsReview dependentsReview(ArtifactStore repositoryStore, CurrentTenant currentTenant,
                                             ObservationRegistry observations) {
        return new DependentsReview(repositoryStore, currentTenant, observations);
    }

}
