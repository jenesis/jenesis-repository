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
 * The two storage roots the console operates on, and the request-scoped tenant view of each.
 *
 * <p><b>They are two roots, not one, and conflating them was a real defect.</b> The console keeps its own documents
 * - the tenant scopes, the user directory, memberships, SCIM tokens, login keys - at the deployment root, beside
 * the repositories. The build cache keeps its projects and entries in a segment inside that same store. While the
 * cache was rooted at the deployment root the two were indistinguishable, so one bean served both and
 * {@code TenantService.all} could derive the tenant list by listing the cache's root and getting the right answer
 * by accident. The moment the cache took a segment of its own that answer became "the tenants that happen to have
 * cache entries", which is not the same set and is not what any caller means.
 *
 * <p>So {@code rootStorage} is a document view over the repository's own store - the multi-tenant root, used for
 * tenant lifecycle and cross-tenant membership - and {@code cacheRootStorage} is the build cache's space, resolved
 * through its SPI. The cache keeps a request-scoped per-tenant view, {@code cacheTenantStorage}, because
 * {@code CacheStorage} is an interface and a scoped proxy over one is free. The console's documents do not - see
 * below.
 *
 * <p><b>The console's documents have no request-scoped bean, deliberately.</b> There was one, and it was a scoped
 * proxy over a class with no interface: as an interface proxy it silently supplied something that was not the
 * tenant's view (a SCIM token written at the root and read from the tenant, surfacing as a valid bearer refused),
 * and as a subclass proxy it needs the owning free-core package opened to Spring on the module path. A caller that
 * wants one tenant's documents scopes the root itself, at the point it means it - one call, and the boundary is
 * visible where it matters.
 *
 * <p>They are two <em>types</em> now, not two beans of one type, which is what stops the first from reading as a
 * cache. The console's own documents are {@link Documents} over the repository store - which is where they always
 * were: this bean was {@code new DelegatingCacheStorage(repositoryStore)}, a cache interface wrapped round the
 * artifact store purely to borrow its file-shaped API. Nothing moved; the borrowed name went.
 *
 * <p>Building the first over {@code repositoryStore} rather than resolving it separately also puts it behind the
 * same {@code jenreg.read-only} choke point the repository server uses, which a separately-resolved root silently
 * bypassed.
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
