package build.jenesis.repository.console.api;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.SettingsEditor;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.spi.Authorization;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the console store's API twins into the repository server, imported through {@code ServerModuleProvider}
 * discovery ({@link ConsoleApiModule}). Each controller takes the console's root storage as an {@link ObjectProvider},
 * resolved lazily: a repository-only composition lacks it, and its endpoints answer {@code 501} rather than stopping
 * the context from booting.
 */
@Configuration(proxyBeanMethods = false)
public class ConsoleApiConfig {

    @Bean
    public ScimTokenController scimTokenController(@Qualifier("rootStorage") ObjectProvider<Documents> storage,
                                                   AuditTrail audit, RepositoryRouting routing) {
        return new ScimTokenController(storage, audit, routing);
    }

    @Bean
    public CacheProjectsController cacheProjectsController(
            @Qualifier("cacheRootStorage") ObjectProvider<CacheStorage> storage, AuditTrail audit,
            RepositoryRouting routing, Repositories repositories, SettingsEditor editor) {
        return new CacheProjectsController(storage, audit, routing, repositories.root(), editor);
    }

    @Bean
    public OriginController originController(Repositories repositories, RepositoryRouting routing) {
        return new OriginController(repositories, routing);
    }

    @Bean
    public BrowseChildrenController browseChildrenController(Repositories repositories, RepositoryRouting routing) {
        return new BrowseChildrenController(repositories, routing);
    }

    /** The tenants, over the repository store's root, which every composition has, so this twin needs no console. */
    @Bean
    public TenantsApiController tenantsApiController(Repositories repositories, Authorization authorization,
                                                     AuditTrail audit, RepositoryProperties properties) {
        String operatorTenant = properties.operatorTenantOrDefault();
        return new TenantsApiController(Documents.over(repositories.root()), repositories.root(), authorization,
                audit, operatorTenant);
    }
}
