package build.jenesis.repository.staging.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.staging.StagedItem;
import build.jenesis.repository.staging.StagingBackend;
import build.jenesis.repository.staging.StagingRepository;
import build.jenesis.repository.staging.StagingState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The staging lifecycle against a recording backend: what each transition does and which transitions are illegal.
 */
class StagingTest {

    private static final StagedItem A = new StagedItem("/maven/org/example/lib/1.0/lib-1.0.jar", "aaaa");
    private static final StagedItem B = new StagedItem("/maven/org/example/lib/1.0/lib-1.0.pom", "bbbb");

    @Test
    void a_fresh_repository_is_open_and_empty() {
        StagingRepository staging = new StagingRepository("s-1", new Recorder());
        assertThat(staging.state()).isEqualTo(StagingState.OPEN);
        assertThat(staging.items()).isEmpty();
    }

    @Test
    void deploys_are_accepted_only_while_open() {
        StagingRepository staging = new StagingRepository("s-1", new Recorder());
        staging.stage(A);
        staging.stage(B);
        assertThat(staging.items()).containsExactly(A, B);
        staging.close();
        assertThatIllegalStateException().isThrownBy(() -> staging.stage(A));
    }

    @Test
    void promotion_re_points_every_item_and_is_terminal() throws IOException {
        Recorder backend = new Recorder();
        StagingRepository staging = new StagingRepository("s-7", backend);
        staging.stage(A);
        staging.stage(B);
        staging.close();
        staging.promote();
        assertThat(staging.state()).isEqualTo(StagingState.PROMOTED);
        assertThat(backend.promoted).containsExactly(A, B);
        assertThat(backend.discarded).isEmpty();
    }

    @Test
    void an_open_repository_cannot_be_promoted() {
        StagingRepository staging = new StagingRepository("s-1", new Recorder());
        staging.stage(A);
        assertThatIllegalStateException().isThrownBy(staging::promote);
    }

    @Test
    void dropping_discards_and_is_terminal() throws IOException {
        Recorder backend = new Recorder();
        StagingRepository staging = new StagingRepository("s-9", backend);
        staging.stage(A);
        staging.drop();
        assertThat(staging.state()).isEqualTo(StagingState.DROPPED);
        assertThat(backend.discarded).containsExactly("s-9");
        assertThat(backend.promoted).isEmpty();
    }

    @Test
    void a_closed_repository_can_still_be_dropped() throws IOException {
        // drop() is legal from OPEN and from CLOSED - a review that rejects a sealed set drops it. Only PROMOTED /
        // DROPPED are terminal. The prior cell drops from OPEN; this pins the supported CLOSED -> DROPPED transition.
        Recorder backend = new Recorder();
        StagingRepository staging = new StagingRepository("s-11", backend);
        staging.stage(A);
        staging.close();
        staging.drop();
        assertThat(staging.state()).isEqualTo(StagingState.DROPPED);
        assertThat(backend.discarded).containsExactly("s-11");
        assertThat(backend.promoted).isEmpty();
    }

    @Test
    void closing_a_non_open_repository_is_rejected() throws IOException {
        Recorder backend = new Recorder();
        StagingRepository closed = new StagingRepository("s-12", backend);
        closed.close();
        assertThatIllegalStateException().as("an already-closed repository cannot be closed again")
                .isThrownBy(closed::close);

        StagingRepository promoted = new StagingRepository("s-13", backend);
        promoted.close();
        promoted.promote();
        assertThatIllegalStateException().as("a promoted repository is terminal, not re-closable")
                .isThrownBy(promoted::close);
    }

    @Test
    void a_promoted_repository_cannot_be_dropped_and_a_dropped_one_cannot_be_promoted() throws IOException {
        Recorder backend = new Recorder();
        StagingRepository promoted = new StagingRepository("s-1", backend);
        promoted.close();
        promoted.promote();
        assertThatIllegalStateException().isThrownBy(promoted::drop);

        StagingRepository dropped = new StagingRepository("s-2", backend);
        dropped.drop();
        assertThatIllegalStateException().isThrownBy(dropped::promote);
    }

    private static final class Recorder implements StagingBackend {

        private final List<StagedItem> promoted = new ArrayList<>();
        private final List<String> discarded = new ArrayList<>();

        @Override
        public void promote(String stagingId, StagedItem item) {
            promoted.add(item);
        }

        @Override
        public void discard(String stagingId) {
            discarded.add(stagingId);
        }
    }
}
