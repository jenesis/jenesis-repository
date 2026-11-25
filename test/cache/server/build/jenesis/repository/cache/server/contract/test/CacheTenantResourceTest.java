package build.jenesis.repository.cache.server.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cache.server.CacheConfig;
import build.jenesis.repository.server.TenantResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cache's URLs name their tenant, {@code /build/<tenant>/...}, which is what a token-exchange request naming a
 * cache URL as its {@code resource} is issued a key for.
 */
class CacheTenantResourceTest {

    private final TenantResource resource = new CacheConfig().cacheTenantResource();

    @Test
    void a_cache_url_names_the_tenant_in_its_first_segment() {
        assertThat(resource.tenant(null, "/build/acme/")).contains("acme");
        assertThat(resource.tenant(null, "/build/acme")).contains("acme");
        assertThat(resource.tenant(null, "/build/acme/cache/0a1b")).contains("acme");
    }

    @Test
    void a_path_outside_the_cache_or_naming_no_tenant_names_none() {
        assertThat(resource.tenant(null, "/repository/acme/releases/")).isEmpty();
        assertThat(resource.tenant(null, "/builds/acme/")).isEmpty();
        assertThat(resource.tenant(null, "/build/")).isEmpty();
        assertThat(resource.tenant(null, "/build//acme")).isEmpty();
        assertThat(resource.tenant(null, "/build/.system/")).isEmpty();
        assertThat(resource.tenant(null, "/build/a b/")).isEmpty();
    }
}
