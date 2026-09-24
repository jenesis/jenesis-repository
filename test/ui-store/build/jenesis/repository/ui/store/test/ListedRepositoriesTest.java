package build.jenesis.repository.ui.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.ui.store.RepositoryAdmin;
import io.micrometer.observation.ObservationRegistry;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The repositories the console's sidebar names, read through the node-local cache. The console asks on every page and
 * swallows a failure into an empty list - a sidebar naming nothing is still a page that renders - so a listing that
 * throws is invisible from the outside. It did: the cache's name is also a metric segment, which admits no hyphen,
 * and every call failed while the sidebar quietly listed nothing.
 */
class ListedRepositoriesTest {

    @TempDir
    Path root;

    @Test
    void the_sidebar_names_the_repositories_the_tenant_holds() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        for (String repository : List.of("releases", "snapshots")) {
            Publication publication = new Publication(store.scope("acme").scope(repository));
            publication.link("/raw/notes.txt", publication.storeBlob(new ByteArrayInputStream("notes".getBytes(UTF_8))));
        }
        RepositoryAdmin admin = new RepositoryAdmin(store, new CurrentTenant() {
            @Override
            public String name() {
                return "acme";
            }
        }, ObservationRegistry.NOOP);

        assertThat(admin.listedRepositories()).containsExactlyInAnyOrder("releases", "snapshots");
    }
}
