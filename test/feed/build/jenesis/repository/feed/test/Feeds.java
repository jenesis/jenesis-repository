package build.jenesis.repository.feed.test;

import build.jenesis.repository.feed.FeedClient;
import build.jenesis.repository.feed.FeedRequest;
import build.jenesis.repository.feed.FeedResponse;
import build.jenesis.repository.feed.FeedTransport;

import module java.base;

/** The shared doubles and fixtures every feed-client test drives: a hand-advanced clock, a recording pause, a
 *  transport that must never be called, and a body-collecting reader. */
final class Feeds {

    /** The one endpoint every fixture queries, so a cross-origin cursor is unambiguous. */
    static final URI ORIGIN = URI.create("https://feed.example/v1/query");

    private Feeds() {
    }

    /** A hand-advanced {@link Clock}, so a deadline is crossed deterministically instead of waited out. */
    static final class TestClock extends Clock {

        private Instant now = Instant.parse("2026-08-08T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** A pause that records what it was asked to wait, and (optionally) advances a clock by it rather than sleeping. */
    static final class RecordingPause implements FeedClient.Pause {

        private final List<Duration> waited = new ArrayList<>();
        private final TestClock clock;

        RecordingPause() {
            this(null);
        }

        RecordingPause(TestClock clock) {
            this.clock = clock;
        }

        @Override
        public void pause(Duration delay) {
            waited.add(delay);
            if (clock != null) {
                clock.advance(delay);
            }
        }

        List<Duration> waited() {
            return List.copyOf(waited);
        }
    }

    /** A pause that reports the calling thread as interrupted - the cancellation leg. */
    static final class InterruptingPause implements FeedClient.Pause {

        @Override
        public void pause(Duration delay) throws InterruptedException {
            throw new InterruptedException("cancelled");
        }
    }

    /** A transport that fails the test if it is called at all - the no-egress guard a read path is asserted with. */
    static final class NoEgressTransport implements FeedTransport {

        @Override
        public FeedResponse send(FeedRequest request, Duration timeout) {
            throw new AssertionError("A read path opened the network: " + request);
        }
    }

    /**
     * A reader that concatenates every page's body and follows a caller-supplied cursor script, recording whether
     * {@link FeedClient.Reader#complete()} was reached - the fixture that proves a capped fetch never yields a
     * partial answer.
     */
    static final class CollectingReader implements FeedClient.Reader<String> {

        private final IntFunction<Optional<FeedRequest>> cursors;
        private final List<String> pages = new ArrayList<>();
        private final AtomicInteger completions;

        CollectingReader(AtomicInteger completions, IntFunction<Optional<FeedRequest>> cursors) {
            this.completions = completions;
            this.cursors = cursors;
        }

        /** A single-page reader: one request, no cursor. */
        static Supplier<CollectingReader> once(AtomicInteger completions) {
            return () -> new CollectingReader(completions, _ -> Optional.empty());
        }

        /** A reader that keeps asking for the same next page forever - the unbounded feed a page cap must refuse. */
        static Supplier<CollectingReader> endless(AtomicInteger completions, URI next) {
            return () -> new CollectingReader(completions, _ -> Optional.of(FeedRequest.get(next)));
        }

        @Override
        public Optional<FeedRequest> read(int page, FeedResponse response) throws IOException {
            pages.add(new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
            return cursors.apply(page);
        }

        @Override
        public String complete() {
            completions.incrementAndGet();
            return String.join("|", pages);
        }
    }
}
