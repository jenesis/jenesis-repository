package build.jenesis.repository.inventory.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.inventory.InventoryStorageNamespace;
import build.jenesis.repository.metadata.MetadataProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The inventory module's storage manifest names every derived per-repository key-space it maintains, so the orphan
 * diagnostic and the explicit operator purge can see them without a hardcoded table. Regression guard (ffe253e): the
 * {@code sizes/} subtree-size roll-ups were declared by no StorageNamespace, so they were purge-invisible and leaked
 * until a manual reap; the manifest must keep declaring them alongside the {@code downloaded} markers and the
 * {@code identity} rollup.
 *
 * <p>The three cut-over spaces - {@code published/}, {@code pinned/} and {@code licenses/} - are declared
 * <em>conditionally</em>, because whether a row under one of them can still be read is a function of the
 * installed metadata persistence module and of nothing else. This module's graph roots that module, so the
 * installed-case answer is the one in force here and the leg that pins it says so; the graceful-absence answer, where
 * the same three roots ARE claimed because they hold the live publish ledger, is a statement about a different module
 * path and is asserted where that path exists.
 */
class InventoryStorageNamespaceTest {

    @Test
    void the_manifest_declares_the_derived_per_repository_key_spaces_including_sizes() {
        Set<String> prefixes = new InventoryStorageNamespace().repositoryPrefixes();
        assertThat(prefixes).as("the sizes/ roll-ups must be purge-visible, not orphaned")
                .contains("downloaded", "identity", "sizes");
    }

    @Test
    void the_manifest_un_claims_only_the_relocated_prefix_that_nothing_reads() {
        assertThat(MetadataProvider.installed())
                .as("the premise: the three spaces are unclaimed only while the persistence module is "
                        + "installed, because only then is a row under them unreachable residue. This module roots "
                        + "build.jenesis.repository.metadata.store, so that is the case asserted below")
                .isPresent();
        assertThat(new InventoryStorageNamespace().repositoryPrefixes())
                .as("licenses/ moved onto the metadata document (§5.4) and nothing reads the sidecar once the "
                        + "module is installed - LicenseInventory.record takes that branch only with no metadata "
                        + "store bound - so a leftover row is unreachable and must surface as an orphan rather "
                        + "than hide under a manifest entry")
                .doesNotContain("licenses");
        assertThat(new InventoryStorageNamespace().repositoryPrefixes())
                .as("published/ is claimed on BOTH layouts, unlike its cut-over sibling: "
                        + "InventoryRecording.membership still probes it to tell 'no record anywhere' from 'a "
                        + "record on the plane this deployment does not read', and the reconcile sweep DELETES on "
                        + "the first answer - so offering it as an orphan invited an operator to purge the key "
                        + "that stops their force-keeps and KEV overrides being reaped")
                .contains("published")
                .as("and the bounded listing faces, written in both layouts, stay claimed as they always were")
                .contains("pinned", "recent");
    }
}
