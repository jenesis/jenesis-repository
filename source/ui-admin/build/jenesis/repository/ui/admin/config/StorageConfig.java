package build.jenesis.repository.ui.admin.config;

import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.cache.storage.CacheStorageProvider;
import build.jenesis.repository.store.ArtifactStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The two storage roots the console operates on. {@code rootStorage} is a {@link Documents} view over the repository's
 * own store, holding the console's documents (tenant scopes, user directory, memberships, SCIM tokens, login keys)
 * behind the same {@code jenrepo.read-only} choke point the server uses; {@code cacheRootStorage} is the build cache's
 * segment, resolved through its SPI. They differ, so a tenant list is never derived from cache entries.
 *
 * <p>A caller scopes either root to a tenant itself, at the call: a tenant the request names, held by whatever
 * outlives the request - a background pass - rather than resolved again where there is no request to resolve it from.
 */
@Configuration
public class StorageConfig {

    @Bean
    public Documents rootStorage(ArtifactStore repositoryStore) {
        return Documents.over(repositoryStore);
    }

    /** The cache's segment of the store this context already holds, so the console opens no second backend client
     *  and its cache operations are metered with the rest. */
    @Bean
    public CacheStorage cacheRootStorage(Environment environment, ArtifactStore repositoryStore) {
        return CacheStorageProvider.resolve(environment::getProperty, repositoryStore);
    }
}
