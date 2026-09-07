package build.jenesis.repository.walk.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import build.jenesis.repository.walk.RebuildPass;
import build.jenesis.repository.walk.WalkConsumer;
import build.jenesis.repository.walk.WalkPass;
import build.jenesis.repository.walk.store.StoreArtifactWalk;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a rebuild pass pays per object, counted at the store. Over an object store every operation is a round trip
 * and a line on a bill, and the pass used to read each key seven times over (a HEAD for its size, the pointer, the
 * four-read servability probe, the blob's length) for ten million objects a day; measured on 2026-09-06 as thirty
 * million reads a day and the largest single cost of a deployment. The bound this holds: per delivered pointer, the
 * pointer itself, the hold chain's probe, its withheld marker and the blob's length - four reads - with the size
 * taken from the listing the walk already paged, and nothing opened.
 */
class RebuildPassCostTest {

    private static final int POINTERS = 40;

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock();

    @Test
    void a_pass_reads_each_pointer_its_marker_and_its_blob_length_and_nothing_else_per_object() throws IOException {
        ArtifactStore filesystem = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        for (int index = 0; index < POINTERS; index++) {
            String hash = filesystem.writeBlob(new ByteArrayInputStream(("content " + index).getBytes(StandardCharsets.UTF_8)));
            filesystem.writeVersioned("publish/maven/com/acme/lib/" + index + "/lib-" + index + ".jar",
                    hash.getBytes(StandardCharsets.UTF_8), null);
        }
        FaultInjectingStore counting = FaultInjectingStore.wrap(filesystem);
        List<String> delivered = new ArrayList<>();
        WalkConsumer consumer = new WalkConsumer() {
            @Override
            public String name() {
                return "counting";
            }

            @Override
            public void onRetained(ArtifactDescriptor artifact, ArtifactStore store) {
                delivered.add(artifact.path());
            }

            @Override
            public void onPassStarted(WalkPass pass) {
            }

            @Override
            public void onPassCompleted(WalkPass pass) {
            }
        };

        Optional<WalkPass> pass = RebuildPass.run(new StoreArtifactWalk(1000, 1, Duration.ofMinutes(10), clock),
                counting, List.of("publish"), List.of(consumer));

        assertThat(pass).isPresent();
        assertThat(delivered).hasSize(POINTERS);
        int reads = counting.calls(FaultInjectingStore.Op.READ_VERSIONED);
        int sizes = counting.calls(FaultInjectingStore.Op.SIZE);
        assertThat(reads).as("the pointer, the hold probe and the withheld marker per object, plus the pass's own documents")
                .isLessThanOrEqualTo(3 * POINTERS + 20);
        assertThat(sizes).as("the blob's length per object; the pointer's size came from the listing, never a HEAD")
                .isLessThanOrEqualTo(POINTERS + 2);
        assertThat(counting.calls(FaultInjectingStore.Op.OPEN)).as("nothing opened: a name is recorded, never re-read").isZero();
        assertThat(counting.calls(FaultInjectingStore.Op.EXISTS)).as("at most one existence probe per object, plus the pass's own").isLessThanOrEqualTo(POINTERS + 10);
        assertThat(counting.calls(FaultInjectingStore.Op.LIST)).as("the walk pages, it does not list").isZero();
    }
}
