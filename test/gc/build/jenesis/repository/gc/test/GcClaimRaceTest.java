package build.jenesis.repository.gc.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.gc.GcPlan;
import build.jenesis.repository.gc.store.MarkSweepGarbageCollector;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import build.jenesis.repository.walk.store.StoreArtifactWalk;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The confirming sweep and a publish of the same condemned bytes, interleaved at the two instants that used to lose
 * an artifact: the sweep had judged the blob and re-read its marker, and a publish clearing the marker and writing its
 * pointer in the moment before the delete was answered {@code 201} for a pointer at nothing. The marker is the arbiter
 * now - the sweep claims it by compare-and-set before it deletes, and a publish spares the blob by compare-and-set on
 * the same marker - so each interleaving has one outcome that keeps every served pointer naming a blob that exists.
 *
 * <p>Both are forced rather than hoped for: the publish runs from inside the store call at which the sweep stands,
 * through the unwrapped store, so it lands exactly between the sweep's judgement and its claim, or between its claim
 * and its delete.
 */
class GcClaimRaceTest {

    private static final String PATH = "/raw/team/tool-1.0.tar.gz";

    private static final byte[] BYTES = "the tool, orphaned, then published again".getBytes(StandardCharsets.UTF_8);

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

    /** Store the bytes with nothing pointing at them and let one pass condemn them. */
    private String condemned(ArtifactStore store) throws IOException {
        String hash = new Publication(store).storeBlob(new ByteArrayInputStream(BYTES));
        assertThat(collector().collect(store, Known.known(List.of("publish")), clock.instant()).condemned())
                .as("the orphan is condemned by one pass").isEqualTo(1);
        return hash;
    }

    @Test
    void a_publish_landing_before_the_claim_keeps_its_bytes() throws IOException {
        ArtifactStore store = store();
        String hash = condemned(store);
        String marker = "gc/condemned/" + hash;
        boolean[] published = {false};
        FaultInjectingStore racing = FaultInjectingStore.wrap(store).tracing((op, key) -> {
            if (op == FaultInjectingStore.Op.WRITE_VERSIONED && marker.equals(key) && !published[0]) {
                published[0] = true;
                try {
                    new Publication(store).link(PATH, hash);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }
        });

        GcPlan confirming = collector().collect(racing, Known.known(List.of("publish")), clock.instant());

        assertThat(published[0]).as("the publish ran at the sweep's claim").isTrue();
        assertThat(confirming.collected()).as("a claim over the marker a publish spared does not land").isZero();
        assertThat(store.exists("blobs/" + hash)).isTrue();
        assertThat(new Publication(store).locate(PATH)).as("the published artifact serves").isPresent();
    }

    @Test
    void a_publish_landing_after_the_claim_is_refused_and_succeeds_once_the_bytes_are_gone() throws IOException {
        ArtifactStore store = store();
        String hash = condemned(store);
        String marker = "gc/condemned/" + hash;
        boolean[] claimed = {false};
        IOException[] refused = {null};
        FaultInjectingStore racing = FaultInjectingStore.wrap(store).tracing((op, key) -> {
            if (op == FaultInjectingStore.Op.WRITE_VERSIONED && marker.equals(key)) {
                claimed[0] = true;
            } else if (op == FaultInjectingStore.Op.EXISTS && ("blobs/" + hash).equals(key) && claimed[0]
                    && refused[0] == null) {
                // Between the claim and the delete: the old guard's re-read had passed here, and the publish's
                // clear and pointer landed unseen before the blob went.
                try {
                    new Publication(store).link(PATH, hash);
                    refused[0] = new IOException("the publish was not refused");
                } catch (IOException failure) {
                    refused[0] = failure;
                }
            }
        });

        GcPlan confirming = collector().collect(racing, Known.known(List.of("publish")), clock.instant());

        assertThat(confirming.collected()).as("the claimed blob is collected").isEqualTo(1);
        assertThat(refused[0]).as("a publish meeting the claim is refused with a retryable answer")
                .isInstanceOf(Publication.BlobCollected.class);
        assertThat(new Publication(store).locate(PATH)).as("and wrote no pointer at the bytes that went").isEmpty();

        Publication again = new Publication(store);
        String stored = again.storeBlob(new ByteArrayInputStream(BYTES));
        again.link(PATH, stored);

        assertThat(store.exists("blobs/" + hash)).as("the retry stores the bytes afresh").isTrue();
        assertThat(again.locate(PATH)).as("and serves them").isPresent();
    }
}
