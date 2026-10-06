package build.jenesis.repository.inventory.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.inventory.InventoryStorageNamespace;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The inventory module's storage manifest names every derived per-repository key-space it maintains, so the orphan
 * diagnostic and the explicit operator purge can see them without a hardcoded table: a space no manifest declares is
 * purge-invisible and leaks until a manual reap. The publish facts, downloads and declared licences are sections of the
 * version documents, which the metadata store's own manifest declares, so none of them is named here.
 */
class InventoryStorageNamespaceTest {

    @Test
    void the_manifest_declares_the_derived_per_repository_key_spaces_and_nothing_the_documents_hold() {
        Set<String> prefixes = new InventoryStorageNamespace().repositoryPrefixes();
        assertThat(prefixes).as("the identity rollup, the sizes roll-ups, the retention policy, the pin index and "
                        + "the newest-first indexes of releases and cached copies, the advisory-name index and the "
                        + "changed versions are purge-visible, not orphaned")
                .containsExactlyInAnyOrder("identity", "sizes", "retention", "pinned", "recent", "cached", "advised",
                        "changed");
    }
}
