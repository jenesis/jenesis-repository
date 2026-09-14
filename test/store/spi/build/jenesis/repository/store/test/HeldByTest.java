package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.HeldBy;
import build.jenesis.repository.store.Publication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The review pointers by the content they hold: a quarantine link writes the entry for its hash, a sweep records a
 * version's served paths under every hash it serves, an unpublish of the review pointer drops it, and a served
 * path survives the trip through the key space it is filed under.
 */
class HeldByTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases");
    }

    @Test
    void a_quarantine_link_indexes_its_hash_and_an_unpublish_forgets_it() throws IOException {
        Publication publication = new Publication(store);
        String hash = publication.storeBlob(new ByteArrayInputStream("held".getBytes(StandardCharsets.UTF_8)));
        String path = "/maven/org/acme/lib/1.0/lib-1.0.jar";
        publication.link(path, hash);
        assertThat(HeldBy.holders(store, hash, 10)).as("an ordinary link is not a hold").isEmpty();

        publication.link("/quarantine" + path, hash);
        assertThat(HeldBy.holders(store, hash, 10)).containsExactly(path);
        publication.link("/quarantine" + path, hash);
        assertThat(HeldBy.holders(store, hash, 10)).as("a re-link adds nothing").containsExactly(path);

        publication.unpublish("/quarantine" + path);
        assertThat(HeldBy.holders(store, hash, 10)).as("the lifted hold leaves the index").isEmpty();
    }

    @Test
    void a_sweep_records_every_served_path_under_every_hash_and_the_paths_round_trip() throws IOException {
        String hash = "a".repeat(64);
        List<String> paths = List.of("/npm/@scope/lib/-/lib-1.0.0.tgz", "/pypi/simple/lib/lib-1.0.0+local.whl",
                "/debian/pool/main/l/lib/lib_1.0-1_amd64.deb");
        HeldBy.record(store, hash, paths);
        assertThat(HeldBy.holders(store, hash, 10)).containsExactlyInAnyOrderElementsOf(paths);
        assertThat(HeldBy.holders(store, hash, 2)).as("a page is a page").hasSize(2);
        HeldBy.forget(store, hash, paths.getFirst());
        HeldBy.forget(store, hash, "/never/indexed");
        assertThat(HeldBy.holders(store, hash, 10)).containsExactlyInAnyOrder(paths.get(1), paths.get(2));
        assertThat(HeldBy.holders(store, "b".repeat(64), 10)).as("a hash nothing holds").isEmpty();
    }

    @Test
    void the_backfill_stamp_is_per_repository() throws IOException {
        assertThat(HeldBy.complete(store)).isFalse();
        HeldBy.completed(store);
        HeldBy.completed(store);
        assertThat(HeldBy.complete(store)).isTrue();
        assertThat(HeldBy.complete(store.scope("other"))).as("another scope is its own repository").isFalse();
    }
}
