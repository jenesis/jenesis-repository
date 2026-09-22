package build.jenesis.repository.inventory.test;

import module java.base;
import module org.junit.jupiter.api;

import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.cleanup.RetentionPolicy;
import build.jenesis.repository.inventory.InventoryStorageNamespace;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The per-repository retention-policy storage the inventory facade delegates to {@code InventoryRetention}: a full
 * policy round-trips through the {@code retention} object with every dial preserved, a keep-last-only policy
 * leaves the optional dials unset, an unset policy reads empty, and - the load-bearing invariant - a stored policy
 * that cannot be parsed fails <em>loudly</em> (an {@link IOException}) rather than reading as "no policy", so silence
 * around a deletion policy never silently disables it. Store-backed over a real filesystem store; no framework.
 *
 * <p>The key itself is asserted, not assumed: the policy is per-repository data, so it is stored at the
 * repository-scoped {@code retention} - a prefix {@code InventoryStorageNamespace} declares - rather than at the old
 * {@code config/retention}, which read like a claim on the deployment-global reserved root and which no manifest entry
 * could describe, leaving every repository's deletion policy behind when the inventory module was purged.
 */
class InventoryRetentionTest {

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

    @Test
    void an_unset_retention_policy_reads_empty() throws IOException {
        assertThat(inventory().readRetention()).isEmpty();
    }

    @Test
    void the_policy_is_stored_at_the_repository_scoped_key_the_manifest_declares() throws IOException {
        inventory().writeRetention(new RetentionPolicy(4));

        assertThat(store.exists("retention"))
                .as("a retention policy is per-repository data, so it sits at a per-repository key - and at one the "
                        + "inventory module declares, or purging that module leaves every repository's deletion "
                        + "policy behind")
                .isTrue();
        assertThat(new InventoryStorageNamespace().repositoryPrefixes())
                .as("the declaration and the key are the same fact stated twice; a key the manifest does not cover is "
                        + "invisible to the orphan diagnostic and unreachable by the operator purge")
                .contains("retention");
        assertThat(store.exists(Scopes.space(Scopes.CONFIG) + "/retention"))
                .as("nothing is written under the deployment-global reserved root any more - that spelling was what "
                        + "made a per-repository object read as a shared claim and kept it out of the manifest")
                .isFalse();
    }

    @Test
    void a_full_policy_round_trips_with_every_dial_preserved() throws IOException {
        RetentionPolicy policy = new RetentionPolicy(5)
                .maxAge(Duration.ofDays(30))
                .prereleaseExpiry(Duration.ofDays(7))
                .notDownloadedFor(Duration.ofDays(90));
        inventory().writeRetention(policy);

        assertThat(inventory().readRetention()).get().satisfies(read -> {
            assertThat(read.keepLast()).isEqualTo(5);
            assertThat(read.maxAge()).isEqualTo(Duration.ofDays(30));
            assertThat(read.prereleaseExpiry()).isEqualTo(Duration.ofDays(7));
            assertThat(read.notDownloadedFor()).isEqualTo(Duration.ofDays(90));
        });
    }

    @Test
    void a_keep_last_only_policy_round_trips_with_the_optional_dials_unset() throws IOException {
        inventory().writeRetention(new RetentionPolicy(3));

        assertThat(inventory().readRetention()).get().satisfies(read -> {
            assertThat(read.keepLast()).isEqualTo(3);
            assertThat(read.maxAge()).isNull();
            assertThat(read.prereleaseExpiry()).isNull();
            assertThat(read.notDownloadedFor()).isNull();
        });
    }

    @Test
    void a_corrupt_stored_policy_fails_loudly_rather_than_reading_as_no_policy() throws IOException {
        // A keepLast that is not an integer: Integer.parseInt throws, and the read must surface it as an IOException,
        // never swallow it into Optional.empty() - which would silently disable an operator's deletion policy.
        store.writeVersioned("retention",
                "keepLast=not-a-number\n".getBytes(StandardCharsets.UTF_8), null);

        assertThatThrownBy(() -> inventory().readRetention())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("corrupt retention policy");
    }
}
