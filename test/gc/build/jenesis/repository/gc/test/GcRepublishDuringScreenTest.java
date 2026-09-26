package build.jenesis.repository.gc.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.gc.store.MarkSweepGarbageCollector;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.walk.store.StoreArtifactWalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A publish of bytes the collector has condemned, with the confirming sweep running while it is in flight.
 *
 * <p>A content-addressed store keeps a blob it already holds and drops the upload, so such a publish relies on the
 * condemned blob from the moment it stores its bytes; it used to un-condemn that blob only when it linked a pointer at
 * the end, after the whole screen. A sweep running in between still found the marker, deleted the blob, and the link
 * wrote a pointer at nothing that answered {@code 201}. {@code GcConcurrentRepublishTest} covers the collector's side
 * of the race - a marker cleared between its judgement and its delete - and this covers the publish's: the marker is
 * gone as soon as the bytes are stored, and a link that finds the blob gone anyway refuses rather than dangles.
 */
class GcRepublishDuringScreenTest {

    private static final String PATH = "/raw/team/tool-1.0.tar.gz";

    private static final byte[] BYTES = "the tool, published, deleted, published again".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock();

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    private MarkSweepGarbageCollector collector() {
        return new MarkSweepGarbageCollector(new StoreArtifactWalk(5, 4, Duration.ofMinutes(10), clock));
    }

    @Test
    void a_confirming_sweep_during_the_publish_of_condemned_bytes_leaves_the_artifact_served() throws IOException {
        ArtifactStore store = store();
        String hash = new Publication(store).storeBlob(new ByteArrayInputStream(BYTES));
        assertThat(collector().collect(store, Known.known(List.of("publish")), clock.instant()).condemned())
                .as("the orphan is condemned by one pass").isEqualTo(1);

        // The same bytes are published again; the layout runs after the store and the screen, which is where the
        // confirming sweep lands - the pass that would delete a blob still carrying its marker.
        Publication.Commit commit = new Publication(store).commit(ArtifactDescriptor.at("raw", PATH),
                new ByteArrayInputStream(BYTES), Publication.Republish.overwrite(), accepted -> {
                    collector().collect(store, Known.known(List.of("publish")), clock.instant());
                    new Publication(store).link(PATH, accepted.artifact().hash());
                    return Publication.Visibility.laidOut();
                });

        assertThat(commit.visible()).isTrue();
        assertThat(store.exists("blobs/" + hash)).as("the sweep found no marker on bytes a publish had just stored")
                .isTrue();
        assertThat(new Publication(store).locate(PATH)).as("and the artifact the publish announced is served")
                .isPresent();
    }

    @Test
    void a_link_that_finds_its_blob_collected_refuses_rather_than_naming_nothing() throws IOException {
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        String hash = publication.storeBlob(new ByteArrayInputStream(BYTES));
        // What a confirming sweep leaves between its two deletes: the blob gone, the marker not yet.
        store.write("gc/condemned/" + hash, new ByteArrayInputStream("1".getBytes(StandardCharsets.UTF_8)));
        store.delete("blobs/" + hash);

        assertThatThrownBy(() -> publication.link(PATH, hash)).isInstanceOf(Publication.BlobCollected.class);
        assertThat(publication.blob(PATH)).as("no pointer at a blob that is not there").isEmpty();
    }
}
