package build.jenesis.repository.gc.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.gc.GcPlan;
import build.jenesis.repository.gc.store.MarkSweepGarbageCollector;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Condemned;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import build.jenesis.repository.walk.store.StoreArtifactWalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The confirming sweep and a publish of the same condemned bytes, interleaved at the two instants that can lose an
 * artifact: the sweep has judged the blob and re-read its marker, and a publish clearing the marker and writing its
 * pointer in the moment before the delete would be answered {@code 201} for a pointer at nothing. The marker is the
 * arbiter - the sweep claims it by compare-and-set before it deletes, and a publish spares the blob by compare-and-set
 * on the same marker - so each interleaving has one outcome that keeps every served pointer naming a blob that exists.
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
                "filesystem", key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
    }

    private MarkSweepGarbageCollector collector() {
        return new MarkSweepGarbageCollector(new StoreArtifactWalk(5, 4, Duration.ofMinutes(10), clock));
    }

    /** A collector whose pass's running time is read off {@code running} rather than the system clock. */
    private MarkSweepGarbageCollector collector(Clock running) {
        return new MarkSweepGarbageCollector(new StoreArtifactWalk(5, 4, Duration.ofMinutes(10), clock),
                Duration.ZERO, List.of(), running);
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

    /**
     * A publish that relied on the bytes while they still stood - its upload dropped as a duplicate of the stored
     * blob - and asks to spare them just after the sweep deleted the blob is refused: it meets the claim the sweep
     * still holds.
     */
    @Test
    void a_publish_meeting_the_sweep_just_after_the_delete_is_refused() throws IOException {
        ArtifactStore store = store();
        String hash = condemned(store);
        String marker = "gc/condemned/" + hash;
        boolean[] deleted = {false};
        IOException[] refused = {null};
        FaultInjectingStore racing = FaultInjectingStore.wrap(store).tracing((op, key) -> {
            if (op == FaultInjectingStore.Op.DELETE && ("blobs/" + hash).equals(key)) {
                deleted[0] = true;
            } else if (deleted[0] && refused[0] == null && marker.equals(key)) {
                // The blob is gone and the sweep is about to record that on its marker.
                refused[0] = publishRelyingOnTheBytes(store, hash);
            }
        });

        collector().collect(racing, Known.known(List.of("publish")), clock.instant());

        assertThat(refused[0]).as("a publish between the delete and the marker's rewrite meets the claim")
                .isInstanceOf(Publication.BlobCollected.class);
        assertThat(new Publication(store).locate(PATH)).as("and wrote no pointer at the bytes that went").isEmpty();
    }

    /**
     * The same publish arriving once the sweep has finished: the marker says the blob was collected, so the publish
     * is refused rather than taking the fast path an absent marker would give it and linking a pointer at nothing.
     * A retry stores the bytes afresh and is spared.
     */
    @Test
    void a_publish_arriving_after_the_sweep_finished_is_refused_until_it_sends_the_bytes_again() throws IOException {
        ArtifactStore store = store();
        String hash = condemned(store);

        assertThat(collector().collect(store, Known.known(List.of("publish")), clock.instant()).collected())
                .isEqualTo(1);

        assertThat(publishRelyingOnTheBytes(store, hash)).as("the marker outlives the blob and refuses the pointer")
                .isInstanceOf(Publication.BlobCollected.class);
        assertThat(new Publication(store).locate(PATH)).isEmpty();

        Publication again = new Publication(store);
        again.link(PATH, again.storeBlob(new ByteArrayInputStream(BYTES)));
        assertThat(again.locate(PATH)).as("the bytes sent again are stored and served").isPresent();
    }

    /**
     * A sweep that died between deleting the blob and recording the delete leaves its claim behind, and once the
     * claim has expired a publish takes it back - writing the marker spared - and is refused, since the blob is gone.
     * A second publish whose upload was dropped as a duplicate while the blob still stood then meets a marker that
     * says spared, and must be refused as well rather than link a pointer at nothing.
     */
    @Test
    void a_publish_meeting_a_marker_spared_over_a_deleted_blob_is_refused() throws IOException {
        ArtifactStore store = store();
        String hash = condemned(store);
        String marker = "gc/condemned/" + hash;
        // The marker's second write of the confirming pass is the rewrite to collected, the claim being its first.
        int[] writes = {0};
        FaultInjectingStore dying = FaultInjectingStore.wrap(store)
                .failNextOn(FaultInjectingStore.Op.WRITE_VERSIONED, key -> key.equals(marker) && ++writes[0] == 2);
        assertThatThrownBy(() -> collector().collect(dying, Known.known(List.of("publish")), clock.instant()))
                .isInstanceOf(IOException.class);
        assertThat(store.exists("blobs/" + hash)).as("the blob went before the sweep died").isFalse();
        // The claim is stamped with the wall clock, which a test cannot advance, so its expiry is written into it.
        String expired = new String(store.readVersioned(marker).orElseThrow().content(), StandardCharsets.UTF_8)
                .replaceAll("claimed=.*", "claimed=" + Instant.now().minus(Condemned.CLAIM_EXPIRY).minusSeconds(60));
        store.write(marker, new ByteArrayInputStream(expired.getBytes(StandardCharsets.UTF_8)));

        assertThat(publishRelyingOnTheBytes(store, hash)).as("the publish taking the expired claim back is refused")
                .isInstanceOf(Publication.BlobCollected.class);
        assertThat(new String(store.readVersioned(marker).orElseThrow().content(), StandardCharsets.UTF_8))
                .as("and leaves the marker spared").startsWith("spared=");

        assertThat(publishRelyingOnTheBytes(store, hash)).as("a duplicate meeting the spared marker is refused too")
                .isInstanceOf(Publication.BlobCollected.class);
        assertThat(new Publication(store).locate(PATH)).as("and no pointer names the bytes that went").isEmpty();

        Publication again = new Publication(store);
        again.link(PATH, again.storeBlob(new ByteArrayInputStream(BYTES)));
        assertThat(again.locate(PATH)).as("the bytes sent again are stored and served").isPresent();
    }

    /**
     * A collection is stamped with the moment the blob went, not the moment the pass began: the stamp opens the
     * window in which a publish that relied on the bytes still meets the marker, so a stamp taken at the start of a
     * sweep that reached the blob forty minutes later would close that window before it had been open for a minute.
     */
    @Test
    void a_collection_is_stamped_when_the_blob_went_rather_than_when_the_pass_began() throws IOException {
        ArtifactStore store = store();
        String hash = condemned(store);
        String marker = "gc/condemned/" + hash;
        MutableClock running = new MutableClock();
        Duration reaching = Duration.ofMinutes(40);
        FaultInjectingStore slow = FaultInjectingStore.wrap(store).tracing((op, key) -> {
            if (op == FaultInjectingStore.Op.DELETE && ("blobs/" + hash).equals(key)) {
                running.advance(reaching);   // the sweep took this long to reach the blob
            }
        });
        Instant began = clock.instant();

        assertThat(collector(running).collect(slow, Known.known(List.of("publish")), began).collected())
                .isEqualTo(1);

        assertThat(Condemned.collectedAt(new String(store.readVersioned(marker).orElseThrow().content(),
                StandardCharsets.UTF_8))).as("the stamp is the delete's").contains(began.plus(reaching));

        clock.advance(reaching.plusMinutes(1));
        var _ = collector().collect(store, Known.known(List.of("publish")), clock.instant());
        assertThat(store.exists(marker)).as("a pass a minute after the delete leaves the marker for the claim window")
                .isTrue();
        assertThat(publishRelyingOnTheBytes(store, hash)).as("so a publish that relied on the bytes still meets it")
                .isInstanceOf(Publication.BlobCollected.class);
    }

    /** Link {@link #PATH} at {@code hash} as a publish whose upload was dropped as a duplicate does - without storing
     *  the bytes - and answer the refusal it met, or an exception saying it met none. */
    private static IOException publishRelyingOnTheBytes(ArtifactStore store, String hash) {
        try {
            new Publication(store).link(PATH, hash);
            return new IOException("the publish was not refused");
        } catch (IOException failure) {
            return failure;
        }
    }
}
