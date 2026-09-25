package build.jenesis.repository.console.api;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.spi.Authorization;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the console store's API twins into the repository server. Imported through {@code ServerModuleProvider}
 * discovery (see {@link ConsoleApiModule}), never named by the server. Each controller takes the console's
 * <em>root</em> storage as an {@link ObjectProvider}, resolved lazily: the storage is wired by the console node, so
 * a repository-only composition does not have it, and asking for it outright would stop that composition booting
 * rather than leaving these endpoints answering {@code 501} - an absent feature is a 501, never a dead context.
 */
@Configuration(proxyBeanMethods = false)
public class ConsoleApiConfig {

    @Bean
    public ScimTokenController scimTokenController(@Qualifier("rootStorage") ObjectProvider<Documents> storage,
                                                   AuditTrail audit, Repositories repositories) {
        return new ScimTokenController(storage, audit, repositories);
    }

    @Bean
    public CacheProjectsController cacheProjectsController(
            @Qualifier("cacheRootStorage") ObjectProvider<CacheStorage> storage, AuditTrail audit,
            Repositories repositories) {
        return new CacheProjectsController(storage, audit, repositories);
    }

    @Bean
    public OriginController originController(Repositories repositories) {
        return new OriginController(repositories);
    }

    /** The tenants, over the documents the console's own tenant directory is built over: the repository store's root,
     *  which every composition has, so this twin needs no console to answer. */
    @Bean
    public TenantsApiController tenantsApiController(Repositories repositories, Authorization authorization,
                                                     AuditTrail audit, RepositoryProperties properties) {
        String operatorTenant = properties.getOperatorTenant().isBlank()
                ? properties.getDefaultTenant()
                : properties.getOperatorTenant();
        return new TenantsApiController(Documents.over(repositories.root()), repositories.root(), authorization,
                audit, operatorTenant);
    }
}
