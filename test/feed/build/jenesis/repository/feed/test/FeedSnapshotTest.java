package build.jenesis.repository.feed.test;

import build.jenesis.repository.feed.FeedClient;
import build.jenesis.repository.feed.FeedException;
import build.jenesis.repository.feed.FeedPolicy;
import build.jenesis.repository.feed.FeedRequest;
import build.jenesis.repository.feed.FeedResponse;
import build.jenesis.repository.feed.FeedSnapshots;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The durable half: a snapshot and its staleness stamp committed together, pointer-last, with the prior-good
 * snapshot retained whenever a refresh does not complete - all through a real filesystem store.
 */
class FeedSnapshotTest {

    private static final String NAMESPACE = "signals/kev";

    @TempDir
    Path root;

    private ArtifactStore store;
    private Feeds.TestClock clock;

    @BeforeEach
    void storeAndClock() {
        // Already tenant-scoped, exactly as a caller hands it in: the client never scopes one itself.
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null).scope("acme");
        clock = new Feeds.TestClock();
    }

    @Test
    void commits_the_snapshot_and_its_staleness_stamp_in_one_write() throws Exception {
        FeedSnapshots snapshots = snapshots();
        FeedClient client = client(FeedPolicy.closed().refreshInterval(Duration.ofHours(6)),
                new RecordedTransport(RecordedTransport.Step.answering(200, "CVE-2021-44228")));

        FeedClient.Answer<FeedSnapshots.Stamp> answer = client.refresh(snapshots,
                FeedRequest.get(Feeds.ORIGIN), body());

        FeedSnapshots.Stamp stamp = answer.value().orElseThrow();
        assertThat(stamp.loaded()).isTrue();
        assertThat(stamp.generation()).isEqualTo(1);
        assertThat(stamp.fetchedAt()).contains(clock.instant());
        assertThat(stamp.nextRefreshAt()).isEqualTo(clock.instant().plus(Duration.ofHours(6)));

        // One pointer object carries both, so a fresh catalogue behind a stale timestamp is unrepresentable.
        FeedSnapshots.Stamp durable = snapshots.current().orElseThrow();
        assertThat(durable).isEqualTo(stamp);
        try (InputStream body = snapshots.open(durable).orElseThrow()) {
            assertThat(new String(body.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("CVE-2021-44228");
        }
    }

    @Test
    void a_snapshot_and_a_fetch_instant_cannot_be_separated() {
        assertThatThrownBy(() -> new FeedSnapshots.Stamp("kev", Optional.empty(), clock.instant(), 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeedSnapshots.Stamp("kev",
                Optional.of(new FeedSnapshots.Snapshot("abc", 3, clock.instant())), clock.instant(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_failed_refresh_keeps_the_prior_good_snapshot_and_only_moves_the_next_attempt() throws Exception {
        FeedSnapshots snapshots = snapshots();
        client(FeedPolicy.closed(), new RecordedTransport(RecordedTransport.Step.answering(200, "good")))
                .refresh(snapshots, FeedRequest.get(Feeds.ORIGIN), body());
        FeedSnapshots.Stamp good = snapshots.current().orElseThrow();

        clock.advance(Duration.ofHours(7));
        FeedClient failing = client(FeedPolicy.closed().maxAttempts(1).retryInterval(Duration.ofMinutes(15)),
                new RecordedTransport(RecordedTransport.Step.answering(503, "unavailable")));

        assertThatThrownBy(() -> failing.refresh(snapshots, FeedRequest.get(Feeds.ORIGIN), body()))
                .isInstanceOf(FeedException.class);

        FeedSnapshots.Stamp after = snapshots.current().orElseThrow();
        assertThat(after.snapshot()).isEqualTo(good.snapshot());        // the same body, the same fetch instant
        assertThat(after.generation()).isEqualTo(good.generation());
        assertThat(after.nextRefreshAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
        try (InputStream body = snapshots.open(after).orElseThrow()) {
            assertThat(new String(body.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("good");
        }
    }

    @Test
    void a_page_cap_never_commits_the_pages_it_did_draw() throws Exception {
        FeedSnapshots snapshots = snapshots();
        client(FeedPolicy.closed(), new RecordedTransport(RecordedTransport.Step.answering(200, "good")))
                .refresh(snapshots, FeedRequest.get(Feeds.ORIGIN), body());
        FeedSnapshots.Stamp good = snapshots.current().orElseThrow();

        FeedClient endless = client(FeedPolicy.closed().maxPages(3),
                new RecordedTransport(RecordedTransport.Step.answering(200, "partial")));

        assertThatThrownBy(() -> endless.refresh(snapshots, FeedRequest.get(Feeds.ORIGIN),
                () -> new SnapshotReader(_ -> Optional.of(FeedRequest.get(Feeds.ORIGIN)))))
                .isInstanceOf(FeedException.class)
                .extracting(failure -> ((FeedException) failure).reason())
                .isEqualTo(FeedException.Reason.PAGE_CAP);

        assertThat(snapshots.current().orElseThrow().snapshot()).isEqualTo(good.snapshot());
    }

    @Test
    void a_feed_that_never_answered_is_not_an_empty_catalogue() throws Exception {
        FeedSnapshots snapshots = snapshots();
        FeedClient client = client(FeedPolicy.soft().maxAttempts(1),
                new RecordedTransport(RecordedTransport.Step.answering(500, "boom")));

        FeedClient.Answer<FeedSnapshots.Stamp> answer = client.refresh(snapshots,
                FeedRequest.get(Feeds.ORIGIN), body());

        assertThat(answer.status()).isEqualTo(FeedClient.Status.DEGRADED);
        FeedSnapshots.Stamp cold = snapshots.current().orElseThrow();
        assertThat(cold.loaded()).isFalse();                       // "tried, nothing yet" - never an authoritative empty
        assertThat(cold.fetchedAt()).isEmpty();
        assertThat(snapshots.open(cold)).isEmpty();
    }

    @Test
    void an_unconfigured_feed_refreshes_nothing_at_all() throws Exception {
        FeedSnapshots snapshots = snapshots();

        FeedClient.Answer<FeedSnapshots.Stamp> answer = FeedClient.unconfigured("vulncheck", "vulncheck-token")
                .refresh(snapshots, FeedRequest.get(Feeds.ORIGIN), body());

        assertThat(answer.status()).isEqualTo(FeedClient.Status.SKIPPED);
        assertThat(snapshots.current()).isEmpty();                 // the store was not touched either
    }

    @Test
    void the_body_is_durable_before_the_pointer_names_it() throws Exception {
        FeedSnapshots snapshots = snapshots();
        client(FeedPolicy.closed(), new RecordedTransport(RecordedTransport.Step.answering(200, "good")))
                .refresh(snapshots, FeedRequest.get(Feeds.ORIGIN), body());
        FeedSnapshots.Stamp good = snapshots.current().orElseThrow();

        // Crash exactly between the body write and the pointer compare-and-set.
        ArtifactStore crashing = new ForwardingStore(store) {

            @Override
            public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
                throw new IOException("the node died before the pointer moved");
            }
        };
        FeedSnapshots interrupted = FeedSnapshots.in(crashing, NAMESPACE, "kev", clock);
        assertThatThrownBy(() -> interrupted.commit("newer".getBytes(StandardCharsets.UTF_8), Duration.ofHours(6)))
                .isInstanceOf(IOException.class);

        // The previous snapshot still serves; the orphaned body is garbage, never a pointer naming absent bytes.
        assertThat(snapshots.current().orElseThrow()).isEqualTo(good);
        assertThat(store.list(NAMESPACE + "/snapshots")).hasSize(2);

        // ... and the next successful refresh collects it.
        clock.advance(Duration.ofHours(7));
        client(FeedPolicy.closed(), new RecordedTransport(RecordedTransport.Step.answering(200, "newest")))
                .refresh(snapshots, FeedRequest.get(Feeds.ORIGIN), body());
        assertThat(store.list(NAMESPACE + "/snapshots")).hasSize(2);   // the new one plus the previous good one
    }

    @Test
    void an_unchanged_catalogue_re_commits_no_bytes_and_only_advances_the_stamp() throws Exception {
        FeedSnapshots snapshots = snapshots();
        client(FeedPolicy.closed(), new RecordedTransport(RecordedTransport.Step.answering(200, "same")))
                .refresh(snapshots, FeedRequest.get(Feeds.ORIGIN), body());
        FeedSnapshots.Stamp first = snapshots.current().orElseThrow();

        clock.advance(Duration.ofHours(7));
        client(FeedPolicy.closed(), new RecordedTransport(RecordedTransport.Step.answering(200, "same")))
                .refresh(snapshots, FeedRequest.get(Feeds.ORIGIN), body());
        FeedSnapshots.Stamp second = snapshots.current().orElseThrow();

        assertThat(second.snapshot().orElseThrow().digest())
                .isEqualTo(first.snapshot().orElseThrow().digest());   // the key IS the content
        assertThat(second.generation()).isEqualTo(2);
        assertThat(second.fetchedAt()).contains(clock.instant());
        assertThat(store.list(NAMESPACE + "/snapshots")).hasSize(1);
    }

    @Test
    void a_racing_refresher_converges_on_the_committed_snapshot() throws Exception {
        FeedSnapshots snapshots = snapshots();
        client(FeedPolicy.closed(), new RecordedTransport(RecordedTransport.Step.answering(200, "winner")))
                .refresh(snapshots, FeedRequest.get(Feeds.ORIGIN), body());
        FeedSnapshots.Stamp winner = snapshots.current().orElseThrow();

        // The loser read the pointer BEFORE the winner committed, so its compare-and-set still expects an absent
        // object - the store double reproduces exactly that stale read while the real object is already there.
        AtomicBoolean beforeTheRace = new AtomicBoolean(true);
        ArtifactStore stale = new ForwardingStore(store) {

            @Override
            public Optional<Versioned> readVersioned(String key) throws IOException {
                return key.endsWith("/current") && beforeTheRace.getAndSet(false)
                        ? Optional.empty()
                        : super.readVersioned(key);
            }
        };
        FeedSnapshots losing = FeedSnapshots.in(stale, NAMESPACE, "kev", clock);

        FeedSnapshots.Stamp adopted = losing.commit("loser".getBytes(StandardCharsets.UTF_8), Duration.ofHours(6));

        assertThat(adopted).isEqualTo(winner);                     // converged, never an alternating overwrite
        assertThat(snapshots.current().orElseThrow()).isEqualTo(winner);
    }

    @Test
    void an_over_large_snapshot_is_refused_rather_than_committed() throws Exception {
        FeedSnapshots snapshots = snapshots();
        FeedClient client = client(FeedPolicy.closed().maxSnapshotBytes(8),
                new RecordedTransport(RecordedTransport.Step.answering(200, "a catalogue far past the cap")));

        assertThatThrownBy(() -> client.refresh(snapshots, FeedRequest.get(Feeds.ORIGIN), body()))
                .isInstanceOf(FeedException.class)
                .extracting(failure -> ((FeedException) failure).reason())
                .isEqualTo(FeedException.Reason.SNAPSHOT_CAP);
        assertThat(snapshots.current().orElseThrow().loaded()).isFalse();
    }

    @Test
    void reading_a_stored_snapshot_reaches_no_network() throws Exception {
        FeedSnapshots snapshots = snapshots();
        client(FeedPolicy.closed(), new RecordedTransport(RecordedTransport.Step.answering(200, "good")))
                .refresh(snapshots, FeedRequest.get(Feeds.ORIGIN), body());

        // The read path holds no transport at all; this asserts it against one that would fail the test if used.
        FeedClient reading = FeedClient.of("kev", new Feeds.NoEgressTransport(), FeedPolicy.closed(), clock,
                new Feeds.RecordingPause());
        FeedSnapshots.Stamp stamp = snapshots.current().orElseThrow();

        assertThat(reading.configured()).isTrue();
        assertThat(stamp.age(clock.instant())).contains(Duration.ZERO);
        assertThat(snapshots.due(clock.instant())).isFalse();
        clock.advance(Duration.ofHours(7));
        assertThat(snapshots.due(clock.instant())).isTrue();
        assertThat(stamp.age(clock.instant())).contains(Duration.ofHours(7));
    }

    @Test
    void a_never_refreshed_feed_is_due_and_renders_no_snapshot() throws Exception {
        FeedSnapshots snapshots = snapshots();

        assertThat(snapshots.current()).isEmpty();
        assertThat(snapshots.due(clock.instant())).isTrue();
    }

    @Test
    void an_unreadable_pointer_heals_on_the_next_commit() throws Exception {
        FeedSnapshots snapshots = snapshots();
        store.writeVersioned(NAMESPACE + "/current", "not a stamp at all".getBytes(StandardCharsets.UTF_8), null);

        assertThat(snapshots.current()).isEmpty();
        FeedSnapshots.Stamp healed = snapshots.commit("fresh".getBytes(StandardCharsets.UTF_8), Duration.ofHours(6));
        assertThat(healed.generation()).isEqualTo(1);
        assertThat(snapshots.current()).contains(healed);
    }

    @Test
    void never_scopes_the_store_it_was_handed() throws Exception {
        ArtifactStore guarded = new ForwardingStore(store) {

            @Override
            public ArtifactStore scope(String tenant) {
                throw new AssertionError("The feed client scoped a store it was handed already scoped: " + tenant);
            }
        };
        FeedSnapshots snapshots = FeedSnapshots.in(guarded, NAMESPACE, "kev", clock);

        client(FeedPolicy.closed(), new RecordedTransport(RecordedTransport.Step.answering(200, "good")))
                .refresh(snapshots, FeedRequest.get(Feeds.ORIGIN), body());

        assertThat(snapshots.current().orElseThrow().loaded()).isTrue();
    }

    @Test
    void a_namespace_can_never_escape_its_tenant() {
        assertThatThrownBy(() -> FeedSnapshots.in(store, "signals/../../etc", "kev", clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeedSnapshots.in(store, "/signals/kev", "kev", clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeedSnapshots.in(store, "", "kev", clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private FeedSnapshots snapshots() {
        return FeedSnapshots.in(store, NAMESPACE, "kev", clock);
    }

    private FeedClient client(FeedPolicy policy, RecordedTransport transport) {
        return FeedClient.of("kev", transport, policy, clock, new Feeds.RecordingPause());
    }

    private Supplier<SnapshotReader> body() {
        return () -> new SnapshotReader(_ -> Optional.empty());
    }

    /** Reduces every page to the bytes the feed persists - the shape a catalogue mirror hands the client. */
    private static final class SnapshotReader implements FeedClient.Reader<byte[]> {

        private final IntFunction<Optional<FeedRequest>> cursors;
        private final ByteArrayOutputStream reduced = new ByteArrayOutputStream();

        private SnapshotReader(IntFunction<Optional<FeedRequest>> cursors) {
            this.cursors = cursors;
        }

        @Override
        public Optional<FeedRequest> read(int page, FeedResponse response) throws IOException {
            response.body().transferTo(reduced);
            return cursors.apply(page);
        }

        @Override
        public byte[] complete() {
            return reduced.toByteArray();
        }
    }
}
