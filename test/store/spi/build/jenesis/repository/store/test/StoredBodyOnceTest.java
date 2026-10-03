package build.jenesis.repository.store.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A body the ingress edge already stored and screened reaches the format's layout as a {@link Publication.Stored}
 * stream, and every way a layout stores a body answers it by the hash it carries: no second write of the blob and no
 * read of it to hash it again. The edge stores a publish's body once; a layout that wrote it again would double the
 * blob traffic of every screened publish while nothing it serves differs.
 */
class StoredBodyOnceTest {

    private static final byte[] BODY = "the bytes the edge stored".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path root;

    @Test
    void every_store_of_an_edge_stored_body_answers_its_hash_without_writing_or_reading_it() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        String hash = new Publication(store).storeBlob(new ByteArrayInputStream(BODY));
        ArtifactDescriptor accepted = ArtifactDescriptor.at("raw", "/raw/once.bin").withBlob(hash, BODY.length);
        FaultInjectingStore counting = FaultInjectingStore.wrap(store);
        Publication publication = new Publication(counting, List.of());

        assertThat(publication.storeBlob(stored(accepted, counting))).isEqualTo(hash);
        assertThat(publication.stored(stored(accepted, counting)))
                .isEqualTo(new Publication.Blob(hash, BODY.length));
        assertThat(Publication.written(counting, stored(accepted, counting)))
                .isEqualTo(new Publication.Blob(hash, BODY.length));
        Publication.Published screened = publication.screen(accepted, stored(accepted, counting));

        assertThat(screened.hash()).as("the screen stamps the hash the body carries").isEqualTo(hash);
        assertThat(screened.size()).isEqualTo(BODY.length);
        assertThat(counting.calls(FaultInjectingStore.Op.WRITE_BLOB))
                .as("no store of an edge-stored body writes the blob again").isZero();
        assertThat(counting.calls(FaultInjectingStore.Op.OPEN))
                .as("nor opens it to hash it a second time").isZero();
    }

    @Test
    void a_body_from_the_network_is_written_once_and_counted() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        FaultInjectingStore counting = FaultInjectingStore.wrap(store);

        Publication.Blob blob = Publication.written(counting, new ByteArrayInputStream(BODY));

        assertThat(blob.size()).as("the length counted as the bytes streamed in").isEqualTo(BODY.length);
        assertThat(counting.calls(FaultInjectingStore.Op.WRITE_BLOB)).isEqualTo(1);
        assertThat(counting.calls(FaultInjectingStore.Op.SIZE)).as("and no stat for it").isZero();
    }

    /** The stream the edge hands a layout: the stored blob, opened lazily on a first read. */
    private static Publication.Stored stored(ArtifactDescriptor accepted, ArtifactStore store) {
        return new Publication.Stored(store, new Publication.Blob(accepted.hash(), accepted.size()));
    }
}
