package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.bounds.GenerationIndex;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two rebuilds of one generation index meeting at the marker - what two nodes produce when one's single-writer lease
 * lapsed mid-rebuild and the other took it. The marker flip used to be a plain write, so the stale rebuild's marker
 * overwrote the newer one's and pointed every reader at a generation the stale rebuild had been writing into beside
 * the newer one, and which the next rebuild would reclaim under them. The flip is a compare-and-set against the marker
 * a rebuild started from: the rebuild whose marker moved loses, says so, and leaves the newer marker standing.
 */
class GenerationIndexRaceTest {

    @Test
    void a_rebuild_whose_marker_moved_under_it_loses_the_flip_and_leaves_the_newer_marker(@TempDir Path root)
            throws Exception {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        GenerationIndex index = new GenerationIndex(store, "rank");
        index.rebuild("s1", prefix -> entry(store, prefix, "first"), index::reclaimFlat);
        GenerationIndex rival = new GenerationIndex(store, "rank");

        // The stale rebuild has read the marker and is writing its generation when the rival - the node that took the
        // lease - rebuilds and flips first.
        assertThatThrownBy(() -> index.rebuild("s2", prefix -> {
            rival.rebuild("s3", other -> entry(store, other, "rival"), rival::reclaimFlat);
            return entry(store, prefix, "stale");
        }, index::reclaimFlat))
                .as("the stale rebuild's flip loses to the marker that moved under it")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("another node");

        assertThat(index.marker()).as("the rival's marker stands").get()
                .satisfies(marker -> assertThat(marker.stamp()).isEqualTo("s3"));
    }

    private static long entry(ArtifactStore store, String prefix, String content) throws IOException {
        store.write(prefix + "/0", new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        return 1;
    }
}
