package build.jenesis.repository.inventory.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two publish-time guards the identity/reconcile suites do not pin: the store-key derivation's traversal defence and
 * the rollup fold's first-publish idempotency.
 *
 * <p>The traversal case drives the PRODUCTION key derivation through the public {@link StoreRepositoryInventory#record}
 * API - the same {@code published/}/{@code MetadataKey} path a real publish takes, whose {@link ArtifactStore#segment}
 * guard rejects a version that is not one traversal-free segment - rather than re-deriving the key by hand (a hand-rolled
 * copy would only test the copy). The idempotency case pins that re-publishing an already-published member folds nothing
 * into the XOR-accumulated rollup identity: the fold is edge-triggered on the absent -&gt; present transition, so a
 * double fold of the same member would XOR it out and corrupt the digest.
 *
 * <p>Mirrors {@link InventoryIdentityTest}'s real filesystem-store setup.
 */
class InventoryPublishGuardsTest {

    private static final String ECO = InventoryTestFormat.ECOSYSTEM;
    private static final String COORD = "com.example:lib";
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases");
    }

    private StoreRepositoryInventory inventory() {
        return new StoreRepositoryInventory(store);
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    @Test
    void a_slash_bearing_version_is_rejected_by_the_production_key_derivation() {
        // "1.0/beta" carries a path separator but no ".." - it is the ArtifactStore.segment guard behind the real
        // publishedKey/MetadataKey derivation, reached through the public record API, that must reject it, not a
        // substring "../" scan. A bare "1.0/beta" (the exact shape a PyPI release label can take) would otherwise fan
        // the member out under a "1.0/" pseudo-coordinate, colliding with a neighbouring version's key-space.
        assertThatThrownBy(() -> inventory().record(ECO, COORD, "1.0/beta", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        // The classic traversal shape is rejected on the same production path.
        assertThatThrownBy(() -> inventory().record(ECO, COORD, "../evil", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void re_publishing_an_already_published_member_leaves_the_rollup_digest_unchanged() throws IOException {
        inventory().record(ECO, COORD, "1.0.0", NOW);
        inventory().record(ECO, COORD, "2.0.0", NOW);
        // First read lazily builds and stores the accumulator over the published set, so a fold at the next record
        // would now be live (the lazy build only runs while no accumulator exists).
        String published = inventory().identity();

        // Re-publish the SAME already-published member, with no eviction between: first-publish edge-detection must
        // no-op the rollup fold. A regression that folded on every publish would XOR this member in a second time,
        // cancelling it out of the accumulator and corrupting the digest.
        inventory().record(ECO, COORD, "1.0.0", NOW);

        assertThat(inventory().identity())
                .as("re-publishing an existing member does not re-fold it into the rollup identity")
                .isEqualTo(published);
        // ...and the incrementally-held value still agrees with an authoritative recompute over the unchanged set.
        assertThat(inventory().identity())
                .as("the untouched incremental digest still agrees with a full rebuild")
                .isEqualTo(hex(inventory().rebuildIdentity()));
    }
}
