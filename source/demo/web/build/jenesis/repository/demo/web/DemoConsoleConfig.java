package build.jenesis.repository.demo.web;

import module java.base;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.demo.DemoContributor;
import build.jenesis.repository.server.RepositoryController;
import build.jenesis.repository.server.kernel.SettingsEditor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.ui.ConsoleTemplates;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;

/**
 * The demo's beans: its page, imported by name rather than scanned; the run; the offer the first-run guide shows; the
 * core's own content; and the resolver that finds the page's template.
 *
 * <p>The run publishes and reads through the repository's own edge, {@link RepositoryController}, resolved when it
 * is used: every shipped image runs the repository beside the console, and a console composed without one reports
 * each publish and read as not made rather than failing to start.
 */
@Configuration(proxyBeanMethods = false)
@Import(DemoController.class)
public class DemoConsoleConfig {

    /** This module's own template namespace, scoped so it can never answer for another module's screen. */
    @Bean
    public SpringResourceTemplateResolver firstRunDemoTemplateResolver(ApplicationContext context) {
        return ConsoleTemplates.resolver(context, DemoConsoleModule.NAME);
    }

    /** The core's own demo content, contributed through the seam another module adds its own by. */
    @Bean
    public StarterDemo starterDemo() {
        return new StarterDemo();
    }

    @Bean
    public DemoRun demoRun(ArtifactStore repositoryStore, SettingsEditor settingsEditor, AuditTrail auditTrail,
                           ObservationRegistry observations, ObjectProvider<DemoContributor> contributors,
                           ObjectProvider<RepositoryController> repository) {
        return new DemoRun(repositoryStore, settingsEditor, auditTrail, observations,
                () -> contributors.stream().toList(), DemoRun.Edge.over(repository::getIfAvailable));
    }

    @Bean
    public DemoOffer demoOffer(DemoRun demoRun) {
        return new DemoOffer(demoRun);
    }
}
