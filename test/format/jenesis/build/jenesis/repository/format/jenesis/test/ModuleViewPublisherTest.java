package build.jenesis.repository.format.jenesis.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.format.jenesis.ModuleViewPublisher;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Jenesis format's cross-publish contribution: given an already-stored blob, {@code publish} links a modular jar
 * into the module layout both by module name and version and by module name alone (the latest), so a client resolving
 * either way reaches the same content-addressed blob - while {@code rebuild}, the seam a repair pass drives, re-links
 * only the version-addressed half and leaves the ordering-dependent "latest" pointer exactly where the last publish
 * put it.
 */
class ModuleViewPublisherTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private Publication publication;
    private final ModuleViewPublisher publisher = new ModuleViewPublisher();

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        publication = new Publication(store);
    }

    @Test
    void publish_links_the_versioned_and_the_latest_module_view_to_one_blob() throws IOException {
        String hash = publication.storeBlob(
                new ByteArrayInputStream("modular jar".getBytes(StandardCharsets.UTF_8)));

        publisher.publish("com.acme.lib", "1.0", hash, store, "/maven/com/acme/lib/1.0/lib-1.0.jar");

        assertThat(publication.located("/module/com.acme.lib/1.0/com.acme.lib.jar")).contains("blobs/" + hash);
        assertThat(publication.located("/module/com.acme.lib/com.acme.lib.jar")).contains("blobs/" + hash);
    }

    @Test
    void rebuild_restores_the_versioned_view_a_crashed_cross_publish_never_linked() throws IOException {
        // The repair seam, over the exact residue MavenFormat.layout can leave: the Maven coordinate is linked and the
        // cross-publish then failed, so no module view exists at all. A rebuild re-derives the version-addressed one.
        String hash = publication.storeBlob(
                new ByteArrayInputStream("modular jar".getBytes(StandardCharsets.UTF_8)));

        publisher.rebuild("com.acme.lib", "1.0", hash, store, "/maven/com/acme/lib/1.0/lib-1.0.jar");

        assertThat(publication.located("/module/com.acme.lib/1.0/com.acme.lib.jar")).contains("blobs/" + hash);
        assertThat(publication.located("/module/com.acme.lib/com.acme.lib.jar"))
                .as("the latest pointer is publication-order truth, so a repair pass never invents one")
                .isEmpty();
    }

    @Test
    void rebuild_never_moves_the_latest_pointer_a_later_publish_owns() throws IOException {
        // Why rebuild is a seam of its own rather than a second call to publish. The walk delivers a repository's jars
        // in path order, not publication order, so a pass that re-linked the latest pointer would move it to whichever
        // version it reached last - here 1.0, silently undoing the 2.0 publish that owns it.
        String older = publication.storeBlob(new ByteArrayInputStream("v1".getBytes(StandardCharsets.UTF_8)));
        String newer = publication.storeBlob(new ByteArrayInputStream("v2".getBytes(StandardCharsets.UTF_8)));
        publisher.publish("com.acme.lib", "1.0", older, store, "/maven/com/acme/lib/1.0/lib-1.0.jar");
        publisher.publish("com.acme.lib", "2.0", newer, store, "/maven/com/acme/lib/2.0/lib-2.0.jar");

        publisher.rebuild("com.acme.lib", "1.0", older, store, "/maven/com/acme/lib/1.0/lib-1.0.jar");

        assertThat(publication.located("/module/com.acme.lib/com.acme.lib.jar"))
                .as("the latest published version still answers by module name alone").contains("blobs/" + newer);
        assertThat(publication.located("/module/com.acme.lib/1.0/com.acme.lib.jar")).contains("blobs/" + older);
        assertThat(publication.located("/module/com.acme.lib/2.0/com.acme.lib.jar")).contains("blobs/" + newer);
    }

    @Test
    void a_repeated_rebuild_lands_the_identical_pointer() throws IOException {
        // Clause 2, which is what makes the repair safe to run on a cadence and to replay after a crash-resume: the
        // second pass over unchanged stored state leaves the same object with the same body.
        String hash = publication.storeBlob(
                new ByteArrayInputStream("modular jar".getBytes(StandardCharsets.UTF_8)));
        publisher.rebuild("com.acme.lib", "1.0", hash, store, "/maven/com/acme/lib/1.0/lib-1.0.jar");
        Optional<ArtifactStore.Versioned> first =
                store.readVersioned("publish/module/com.acme.lib/1.0/com.acme.lib.jar");

        publisher.rebuild("com.acme.lib", "1.0", hash, store, "/maven/com/acme/lib/1.0/lib-1.0.jar");

        assertThat(store.readVersioned("publish/module/com.acme.lib/1.0/com.acme.lib.jar"))
                .get().extracting(ArtifactStore.Versioned::content)
                .isEqualTo(first.orElseThrow().content());
    }
}
