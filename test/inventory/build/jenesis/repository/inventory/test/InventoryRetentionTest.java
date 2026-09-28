package build.jenesis.repository.inventory.test;

import module java.base;
import module org.junit.jupiter.api;

import build.jenesis.repository.inventory.InventoryStorageNamespace;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The retention policy a repository stored before its rules were repository settings, as the one-time move reads it
 * ({@code StoreRepositoryInventory.formerRetention}): the {@code retention} object's four dials come back as the
 * policy, an unset object reads empty, and - the load-bearing invariant - a stored policy that cannot be parsed fails
 * <em>loudly</em> (an {@link IOException}) rather than reading as "no policy", so the move says it could not carry a
 * deletion policy rather than silently dropping it. The object stays declared by the inventory's storage namespace,
 * so the module purge still reaches what the move left in place.
 */
class InventoryRetentionTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases");
    }

    private StoreRepositoryInventory inventory() {
        return new StoreRepositoryInventory(store);
    }

    @Test
    void an_unset_former_policy_reads_empty() throws IOException {
        assertThat(inventory().formerRetention()).isEmpty();
    }

    @Test
    void the_former_policy_is_read_from_the_repository_scoped_key_the_manifest_declares() throws IOException {
        store.writeVersioned("retention", ("keepLast=5\nmaxAge=PT720H\nprereleaseExpiry=PT168H\n"
                + "notDownloadedFor=PT2160H\n").getBytes(StandardCharsets.UTF_8), null);

        assertThat(inventory().formerRetention()).get().satisfies(read -> {
            assertThat(read.keepLast()).isEqualTo(5);
            assertThat(read.maxAge()).isEqualTo(Duration.ofDays(30));
            assertThat(read.prereleaseExpiry()).isEqualTo(Duration.ofDays(7));
            assertThat(read.notDownloadedFor()).isEqualTo(Duration.ofDays(90));
        });
        assertThat(new InventoryStorageNamespace().repositoryPrefixes())
                .as("the object the move leaves in place stays one the inventory module declares, so its purge "
                        + "still reaches it")
                .contains("retention");
    }

    @Test
    void a_keep_last_only_policy_reads_with_the_optional_dials_unset() throws IOException {
        store.writeVersioned("retention", "keepLast=3\n".getBytes(StandardCharsets.UTF_8), null);

        assertThat(inventory().formerRetention()).get().satisfies(read -> {
            assertThat(read.keepLast()).isEqualTo(3);
            assertThat(read.maxAge()).isNull();
            assertThat(read.prereleaseExpiry()).isNull();
            assertThat(read.notDownloadedFor()).isNull();
        });
    }

    @Test
    void a_corrupt_stored_policy_fails_loudly_rather_than_reading_as_no_policy() throws IOException {
        // A keepLast that is not an integer: Integer.parseInt throws, and the read must surface it as an IOException,
        // never swallow it into Optional.empty() - which would silently drop an operator's deletion policy.
        store.writeVersioned("retention",
                "keepLast=not-a-number\n".getBytes(StandardCharsets.UTF_8), null);

        assertThatThrownBy(() -> inventory().formerRetention())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("corrupt retention policy");
    }
}
