package build.jenesis.repository.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.StoreCache;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a request pays to be authorised, counted at the store: the credential's two documents are read once and then
 * served from the {@link StoreCache} for its ttl, the address check re-uses the entry the grant check filled, and a
 * grant or revocation on this node is seen by its next request - so a download is authorised for zero store reads
 * in the steady state, where it used to pay three per request, a third of the read path's round trips.
 *
 * <p><b>Three reads rather than two, and the third is the deployment's auth epoch</b> - one small document a node
 * re-reads at most once per {@code Authorization.EPOCH_TTL} (seconds), clearing its credential cache when another
 * node has changed a credential. It is what lets the credential ttl be fifteen minutes without a revocation taking
 * fifteen minutes to reach the fleet, and {@code POST /api/admin/caches/clear} only ever clearing one node. The
 * claim this suite exists for is untouched and is the second assertion, not the first: **a hundred further requests
 * still cost no store read at all**, because the epoch is paid per window and not per request.
 */
class AuthorizationCacheTest {

    @TempDir
    Path root;

    private FaultInjectingStore store;
    private Authorization authorization;

    @BeforeEach
    void setUp() {
        ArtifactStore filesystem = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        store = FaultInjectingStore.wrap(filesystem);
        authorization = Authorization.enforcing(store);
    }

    @Test
    void a_request_is_authorised_from_the_store_once_and_from_the_cache_after_that() throws IOException {
        String key = Authorization.mint("acme");
        authorization.setGrant("acme", Authorization.hash(key), "releases", Authorization.REPOSITORY_READ);
        long before = store.calls(FaultInjectingStore.Op.READ_VERSIONED);

        assertThat(authorization.authorize(key, "releases", "com/acme/lib/1.0/lib.jar", Authorization.REPOSITORY_READ))
                .isEqualTo(Authorization.Decision.ALLOWED);
        assertThat(authorization.addressAllowed(key, "10.0.0.7")).isTrue();
        long first = store.calls(FaultInjectingStore.Op.READ_VERSIONED) - before;
        assertThat(first).as("the metadata and the grants once each - the address check re-uses the metadata - plus the "
                + "auth epoch, read once per window and not per request").isEqualTo(3);

        for (int request = 0; request < 100; request++) {
            assertThat(authorization.authorize(key, "releases", "com/acme/lib/1.0/lib.jar", Authorization.REPOSITORY_READ))
                    .isEqualTo(Authorization.Decision.ALLOWED);
            assertThat(authorization.addressAllowed(key, "10.0.0.7")).isTrue();
        }
        assertThat(store.calls(FaultInjectingStore.Op.READ_VERSIONED) - before)
                .as("a hundred more requests cost no store read: the read path pays for nothing twice, and the "
                        + "epoch is inside its window").isEqualTo(3);
    }

    @Test
    void a_revocation_on_this_node_stops_the_key_at_once_and_an_unknown_key_is_remembered_as_unknown() throws IOException {
        String key = Authorization.mint("acme");
        authorization.setGrant("acme", Authorization.hash(key), "releases", Authorization.REPOSITORY_READ);
        assertThat(authorization.authorize(key, "releases", null, Authorization.REPOSITORY_READ))
                .isEqualTo(Authorization.Decision.ALLOWED);

        authorization.revoke("acme", Authorization.hash(key));
        assertThat(authorization.authorize(key, "releases", null, Authorization.REPOSITORY_READ))
                .as("write-through: the node that revoked sees it on the next request").isEqualTo(Authorization.Decision.FORBIDDEN);

        String stranger = Authorization.mint("acme");
        long before = store.calls(FaultInjectingStore.Op.READ_VERSIONED);
        for (int request = 0; request < 10; request++) {
            assertThat(authorization.authorize(stranger, "releases", null, Authorization.REPOSITORY_READ))
                    .isEqualTo(Authorization.Decision.FORBIDDEN);
        }
        assertThat(store.calls(FaultInjectingStore.Op.READ_VERSIONED) - before)
                .as("an unprovisioned key is asked of the store once, not per request - and the epoch is not "
                        + "re-read, because the revocation above already freshened it inside the window").isEqualTo(2);
    }

    @Test
    void the_authorization_cache_is_on_the_registry_the_clear_reaches() throws IOException {
        String key = Authorization.mint("acme");
        authorization.setGrant("acme", Authorization.hash(key), "releases", Authorization.REPOSITORY_READ);
        authorization.authorize(key, "releases", null, Authorization.REPOSITORY_READ);
        assertThat(StoreCache.caches()).extracting(StoreCache::name).contains("authorization");
        long before = store.calls(FaultInjectingStore.Op.READ_VERSIONED);
        StoreCache.clearAll();
        authorization.authorize(key, "releases", null, Authorization.REPOSITORY_READ);
        assertThat(store.calls(FaultInjectingStore.Op.READ_VERSIONED) - before)
                .as("after a clear the store is asked again").isEqualTo(2);
    }
}
