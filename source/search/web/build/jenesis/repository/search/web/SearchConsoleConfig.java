package build.jenesis.repository.search.web;

import build.jenesis.repository.ui.ConsoleTemplates;
import org.springframework.context.ApplicationContext;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.ui.store.RepositoryBrowse;
import build.jenesis.repository.ui.store.SettingsAdmin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** This feature's console beans: its screen, and the templates that travel with the module. */
@Configuration(proxyBeanMethods = false)
public class SearchConsoleConfig {

    /** The template namespace this module's resolver answers for, so its pages cannot shadow another module's. */
    public static final String QUALIFIER = "search";

    @Bean
    public LicenceInventoryScreenController searchScreenController(RepositoryBrowse browse, SettingsAdmin settings,
                                                                   CurrentTenant tenant) {
        return new LicenceInventoryScreenController(browse, settings, tenant);
    }

    @Bean
    public SpringResourceTemplateResolver searchTemplateResolver(ApplicationContext context) {
        return ConsoleTemplates.resolver(context, QUALIFIER);
    }
}
