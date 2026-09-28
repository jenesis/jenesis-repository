package build.jenesis.repository.publication.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A coordinate that several layouts serve links to the folder that holds it. Ivy and Maven both declare the
 * {@code Maven} ecosystem, so both resolve a folder for a Maven coordinate, and only the one the repository published
 * into holds anything; the first layout by name is Ivy, which linked every Maven coordinate into an empty folder.
 */
class CoordinateFolderTest {

    @TempDir
    Path root;

    @Test
    void the_folder_that_holds_the_version_is_the_one_linked() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("acme").scope("releases");
        Publication publication = new Publication(store);
        publication.link("/maven/org/acme/app/1.0/app-1.0.pom",
                publication.storeBlob(new ByteArrayInputStream("<project/>".getBytes(StandardCharsets.UTF_8))));

        assertThat(new StoreRepositoryInventory(store).locateHeld("Maven", "org.acme:app", "1.0"))
                .as("the Maven folder, which holds the version, rather than the Ivy one, which resolves but is empty")
                .startsWith("/maven/");
    }

    @Test
    void a_version_no_folder_holds_links_where_a_layout_would_place_it() {
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("acme").scope("empty"));

        assertThat(inventory.locateHeld("Maven", "org.acme:app", "1.0"))
                .isEqualTo(inventory.locate("Maven", "org.acme:app", "1.0"));
    }
}
