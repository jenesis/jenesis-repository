package build.jenesis.repository.ui.admin.config;

import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.cache.storage.CacheStorageProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.ui.CurrentTenant;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.core.env.Environment;
import org.springframework.web.context.WebApplicationContext;

/**
 * The two storage roots the console operates on. {@code rootStorage} is a {@link Documents} view over the repository's
 * own store, holding the console's documents (tenant scopes, user directory, memberships, SCIM tokens, login keys)
 * behind the same {@code jenrepo.read-only} choke point the server uses; {@code cacheRootStorage} is the build cache's
 * segment, resolved through its SPI. They differ, so a tenant list is never derived from cache entries.
 *
 * <p>The cache keeps a request-scoped per-tenant view, {@code cacheTenantStorage}, since a scoped proxy over the
 * {@code CacheStorage} interface is free. {@link Documents} has no interface, so a caller scopes the root to a tenant
 * itself rather than through a proxy.
 */
@Configuration
public class StorageConfig {

    @Bean
    public Documents rootStorage(ArtifactStore repositoryStore) {
        return Documents.over(repositoryStore);
    }

    @Bean
    public CacheStorage cacheRootStorage(Environment environment) {
        return CacheStorageProvider.resolve(environment::getProperty);
    }

    @Bean
    @Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = ScopedProxyMode.INTERFACES)
    public CacheStorage cacheTenantStorage(@Qualifier("cacheRootStorage") CacheStorage cacheRootStorage,
                                           CurrentTenant current) {
        return cacheRootStorage.scope(selected(current));
    }

    private static String selected(CurrentTenant current) {
        String tenant = current.name();
        if (tenant == null) {
            throw new IllegalStateException("No tenant selected.");
        }
        return tenant;
    }
}
