package build.jenesis.repository.gc.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.gc.GcPlan;
import build.jenesis.repository.gc.store.MarkSweepGarbageCollector;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Condemned;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import build.jenesis.repository.walk.store.StoreArtifactWalk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Kill-and-resume at each phase boundary, with the shared fault-injecting store standing in for a dying node: a
 * crashed reference flush can never let a referenced blob be deleted (the walk's flush-before-checkpoint ordering
 * keeps the committed cursor honest), a crash between the blob and marker deletes converges on the next pass, and
 * a crash between mark and sweep costs a pass, never an artifact.
 */
class GcRecoveryTest {

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock();

    private ArtifactStore filesystem() {
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    private MarkSweepGarbageCollector collector() {
        return new MarkSweepGarbageCollector(new StoreArtifactWalk(2, 1, Duration.ofMinutes(10), clock));
    }

    private static ByteArrayInputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void a_crashed_reference_flush_never_lets_a_referenced_blob_be_deleted() throws IOException {
        // The nastiest window for the absolute invariant: the blob's only pointer is visited by the mark, the
        // flush of its reference batch dies, and the blob is already due (an "earlier pass" condemned marker).
        // Were the cursor committed before the flush, the resumed mark would skip the pointer, the reference
        // would be lost, and the sweep would delete a blob that serves.
        ArtifactStore inner = filesystem();
        Publication publication = new Publication(inner);
        String kept = publication.storeBlob(bytes("kept"));
        inner.writeVersioned("publish/aa/kept.jar", kept.getBytes(StandardCharsets.UTF_8), null);
        inner.writeVersioned("publish/bb/pad.jar", kept.getBytes(StandardCharsets.UTF_8), null);
        inner.writeVersioned("publish/cc/pad.jar", kept.getBytes(StandardCharsets.UTF_8), null);
        inner.writeVersioned("gc/condemned/" + kept,
                "pass=0\nsince=2026-07-01T00:00:00Z".getBytes(StandardCharsets.UTF_8), null);

        FaultInjectingStore store = FaultInjectingStore.wrap(inner)
                .failEveryOn(FaultInjectingStore.Op.WRITE_VERSIONED, FaultInjectingStore.keyPrefix("gc/1/refs"));
        assertThatThrownBy(() -> collector().collect(store, Known.known(List.of("publish")), clock.instant()))
                .isInstanceOf(IOException.class);
        assertThat(new StoreArtifactWalk(2, 1, Duration.ofMinutes(10), clock)
                .segments(inner, "gc-mark"))
                .as("the cursor never advanced past the unflushed references")
                .allSatisfy(segment -> assertThat(segment.cursor()).isNull());

        store.heal();
        clock.advance(Duration.ofMinutes(11)); // the dead worker's claim expires
        GcPlan resumed = collector().collect(store, Known.known(List.of("publish")), clock.instant());
        assertThat(resumed.complete()).isTrue();
        assertThat(inner.exists("blobs/" + kept))
                .as("the resumed mark re-read the pointer, so the referenced blob survives").isTrue();
        assertThat(resumed.spared()).isEqualTo(1);
        assertThat(inner.exists("gc/condemned/" + kept)).as("and its stale marker converged away").isFalse();
    }

    @Test
    void a_crash_between_the_blob_and_marker_deletes_converges() throws IOException {
        ArtifactStore inner = filesystem();
        Publication publication = new Publication(inner);
        String kept = publication.storeBlob(bytes("kept"));
        publication.link("/maven/kept.jar", kept);
        String orphan = publication.storeBlob(bytes("orphan"));
        var _ = collector().collect(inner, Known.known(List.of("publish")), clock.instant());
        assertThat(inner.exists("gc/condemned/" + orphan)).isTrue();

        // The crash lands between the blob's delete and the marker's rewrite to collected: the marker's second write
        // of the pass, the claim being its first.
        String marker = "gc/condemned/" + orphan;
        int[] writes = {0};
        FaultInjectingStore store = FaultInjectingStore.wrap(inner)
                .failNextOn(FaultInjectingStore.Op.WRITE_VERSIONED, key -> key.equals(marker) && ++writes[0] == 2);
        assertThatThrownBy(() -> collector().collect(store, Known.known(List.of("publish")), clock.instant()))
                .isInstanceOf(IOException.class);
        assertThat(inner.exists("blobs/" + orphan)).as("the blob went before the crash").isFalse();
        assertThat(inner.exists(marker)).as("its claim is the crash residue").isTrue();
        assertThatThrownBy(() -> new Publication(inner).link("/maven/relied.jar", orphan))
                .as("and a publish that relied on the bytes meets the claim rather than an absence")
                .isInstanceOf(Publication.BlobCollected.class);

        store.heal();
        clock.advance(Duration.ofMinutes(11));
        var _ = collector().collect(inner, Known.known(List.of("publish")), clock.instant());
        assertThat(inner.exists(marker)).as("a claim still within its window stays").isTrue();

        // The claim is stamped with the wall clock, which a test cannot advance, so the window passing is written
        // into the marker instead.
        String aged = new String(inner.readVersioned(marker).orElseThrow().content(), StandardCharsets.UTF_8)
                .replaceAll("claimed=.*", "claimed=" + Instant.now().minus(Condemned.CLAIM_EXPIRY).minusSeconds(60));
        inner.write(marker, new ByteArrayInputStream(aged.getBytes(StandardCharsets.UTF_8)));
        GcPlan converged = collector().collect(inner, Known.known(List.of("publish")), clock.instant());
        assertThat(converged.complete()).isTrue();
        assertThat(inner.exists(marker))
                .as("a marker whose blob is gone is swept by the convergence leg once its claim has expired").isFalse();
        assertThat(inner.exists("blobs/" + kept)).isTrue();
    }

    @Test
    void a_crash_between_mark_and_sweep_costs_a_pass_never_an_artifact() throws IOException {
        ArtifactStore inner = filesystem();
        Publication publication = new Publication(inner);
        String kept = publication.storeBlob(bytes("kept"));
        publication.link("/maven/kept.jar", kept);
        String orphan = publication.storeBlob(bytes("orphan"));

        // The sweep walk dies before it can claim anything: the completed mark stands, nothing was judged.
        FaultInjectingStore store = FaultInjectingStore.wrap(inner)
                .failEveryOn(FaultInjectingStore.Op.WRITE_VERSIONED, FaultInjectingStore.keyPrefix("walks/gc-sweep"));
        assertThatThrownBy(() -> collector().collect(store, Known.known(List.of("publish")), clock.instant()))
                .isInstanceOf(IOException.class);
        assertThat(inner.exists("blobs/" + orphan)).isTrue();

        store.heal();
        clock.advance(Duration.ofMinutes(11));
        var _ = collector().collect(inner, Known.known(List.of("publish")), clock.instant());
        var _ = collector().collect(inner, Known.known(List.of("publish")), clock.instant());
        assertThat(inner.exists("blobs/" + orphan)).as("the orphan still converges to collected").isFalse();
        assertThat(inner.exists("blobs/" + kept)).isTrue();
        // The collected blob's marker outlives it for the claim window, then converges away.
        clock.advance(Condemned.CLAIM_EXPIRY.plusMinutes(1));
        var _ = collector().collect(inner, Known.known(List.of("publish")), clock.instant());
        assertThat(inner.list("gc/condemned")).isEmpty();
    }
}
