package build.jenesis.repository.publication.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A version's browse folder is located through the repository's own format where several installed formats address
 * its ecosystem: an Ivy and a Maven layout both place Maven coordinates, and a search hit in a Maven repository that
 * linked the Ivy folder would open a folder the repository never holds.
 */
class LocateOwnLayoutTest {

    @TempDir
    Path root;

    private ArtifactStore repository(String name, String type) throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope(name);
        RepositoryType.create(store, type);
        return store;
    }

    @Test
    void a_maven_repository_locates_a_maven_version_in_its_maven_folder() throws IOException {
        String located = new StoreRepositoryInventory(repository("releases", "maven"))
                .locate("Maven", "build.jenesis.soak:soak-maven-1", "1.0.0");
        assertThat(located).as("the Maven layout's folder, not the Ivy one").startsWith("/maven/");
    }

    @Test
    void an_ivy_repository_locates_the_same_version_in_its_ivy_folder() throws IOException {
        String located = new StoreRepositoryInventory(repository("modules", "ivy"))
                .locate("Maven", "build.jenesis.soak:soak-maven-1", "1.0.0");
        assertThat(located).as("the Ivy layout's folder").startsWith("/ivy/");
    }
}
