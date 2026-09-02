package build.jenesis.repository.feed.test;

import module java.base;

import build.jenesis.repository.feed.FeedClient;
import build.jenesis.repository.feed.FeedException;
import build.jenesis.repository.feed.FeedPolicy;
import build.jenesis.repository.feed.FeedRequest;
import build.jenesis.repository.feed.FeedResponse;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bounded pagination and the decisive property: reaching the page cap never yields a bounded-but-incomplete answer.
 * The fixture counts {@code complete()} calls, so "no partial answer escaped" is asserted rather than assumed.
 */
class FeedPaginationTest {

    private final AtomicInteger completions = new AtomicInteger();

    @Test
    void follows_the_cursor_through_every_page_before_answering() throws Exception {
        RecordedTransport transport = new RecordedTransport(
                RecordedTransport.Step.answering(200, "one"),
                RecordedTransport.Step.answering(200, "two"),
                RecordedTransport.Step.answering(200, "three"));
        FeedClient client = FeedClient.of("osv", transport, FeedPolicy.closed());

        FeedClient.Answer<String> answer = client.fetch(FeedRequest.get(Feeds.ORIGIN),
                () -> new Feeds.CollectingReader(completions, page -> page < 3
                        ? Optional.of(FeedRequest.get(Feeds.ORIGIN.resolve("/v1/query?page=" + (page + 1))))
                        : Optional.empty()));

        assertThat(answer.value()).contains("one|two|three");
        assertThat(transport.sent()).isEqualTo(3);
        assertThat(transport.requests().get(2).uri().getQuery()).isEqualTo("page=3");
    }

    @Test
    void the_page_cap_fails_visibly_and_never_completes_a_partial_answer() {
        RecordedTransport transport = new RecordedTransport(RecordedTransport.Step.answering(200, "page"));
        FeedClient client = FeedClient.of("osv", transport, FeedPolicy.closed().maxPages(4));

        assertThatThrownBy(() -> client.fetch(FeedRequest.get(Feeds.ORIGIN),
                Feeds.CollectingReader.endless(completions, Feeds.ORIGIN)))
                .isInstanceOf(FeedException.class)
                .hasMessageContaining("4-page cap")
                .hasMessageContaining("bounded-but-incomplete")
                .extracting(failure -> ((FeedException) failure).reason())
                .isEqualTo(FeedException.Reason.PAGE_CAP);

        assertThat(transport.sent()).isEqualTo(4);         // exactly the cap was drawn, never a page more
        assertThat(completions).hasValue(0);               // and the half-filled answer was never completed
    }

    @Test
    void the_page_cap_degrades_rather_than_half_answering_under_a_soft_policy() throws Exception {
        RecordedTransport transport = new RecordedTransport(RecordedTransport.Step.answering(200, "page"));
        FeedClient client = FeedClient.of("scorecard", transport, FeedPolicy.soft().maxPages(2));

        FeedClient.Answer<String> answer = client.fetch(FeedRequest.get(Feeds.ORIGIN),
                Feeds.CollectingReader.endless(completions, Feeds.ORIGIN));

        assertThat(answer.status()).isEqualTo(FeedClient.Status.DEGRADED);
        assertThat(answer.value()).isEmpty();
        assertThat(completions).hasValue(0);
    }

    @Test
    void a_cursor_leaving_the_origin_is_refused() {
        RecordedTransport transport = new RecordedTransport(RecordedTransport.Step.answering(200, "page"));
        FeedClient client = FeedClient.of("github", transport, FeedPolicy.closed());

        assertThatThrownBy(() -> client.fetch(FeedRequest.get(Feeds.ORIGIN).bearer("s3cret"),
                () -> new Feeds.CollectingReader(completions,
                        _ -> Optional.of(FeedRequest.get(URI.create("https://169.254.169.254/latest/meta-data"))))))
                .isInstanceOf(FeedException.class)
                .extracting(failure -> ((FeedException) failure).reason())
                .isEqualTo(FeedException.Reason.CROSS_ORIGIN);

        assertThat(transport.sent()).isEqualTo(1);         // the hostile cursor was never dereferenced
    }

    @Test
    void a_cursor_on_another_port_or_scheme_is_a_different_origin() {
        assertThat(FeedRequest.sameOrigin(Feeds.ORIGIN, URI.create("https://feed.example/v1/query?page=2"))).isTrue();
        assertThat(FeedRequest.sameOrigin(Feeds.ORIGIN, URI.create("https://feed.example:443/x"))).isTrue();
        assertThat(FeedRequest.sameOrigin(Feeds.ORIGIN, URI.create("https://FEED.example/x"))).isTrue();
        assertThat(FeedRequest.sameOrigin(Feeds.ORIGIN, URI.create("http://feed.example/x"))).isFalse();
        assertThat(FeedRequest.sameOrigin(Feeds.ORIGIN, URI.create("https://feed.example:8443/x"))).isFalse();
        assertThat(FeedRequest.sameOrigin(Feeds.ORIGIN, URI.create("https://evil.example/x"))).isFalse();
    }

    @Test
    void a_deployment_that_paginates_across_hosts_can_say_so() throws Exception {
        RecordedTransport transport = new RecordedTransport(
                RecordedTransport.Step.answering(200, "one"),
                RecordedTransport.Step.answering(200, "two"));
        FeedClient client = FeedClient.of("mirror", transport, FeedPolicy.closed().sameOriginOnly(false));

        FeedClient.Answer<String> answer = client.fetch(FeedRequest.get(Feeds.ORIGIN),
                () -> new Feeds.CollectingReader(completions, page -> page < 2
                        ? Optional.of(FeedRequest.get(URI.create("https://mirror.example/page2")))
                        : Optional.empty()));

        assertThat(answer.value()).contains("one|two");
    }

    @Test
    void a_retry_starts_a_fresh_accumulator_so_no_page_is_folded_twice() throws Exception {
        Feeds.TestClock clock = new Feeds.TestClock();
        Feeds.RecordingPause pause = new Feeds.RecordingPause(clock);
        RecordedTransport transport = new RecordedTransport(
                RecordedTransport.Step.answering(200, "one"),
                RecordedTransport.Step.failing(new IOException("connection reset")),
                RecordedTransport.Step.answering(200, "one"),
                RecordedTransport.Step.answering(200, "two"));
        FeedClient client = FeedClient.of("osv", transport, FeedPolicy.closed().maxAttempts(2), clock, pause);

        FeedClient.Answer<String> answer = client.fetch(FeedRequest.get(Feeds.ORIGIN),
                () -> new Feeds.CollectingReader(completions, page -> page < 2
                        ? Optional.of(FeedRequest.get(Feeds.ORIGIN.resolve("/v1/query?page=2")))
                        : Optional.empty()));

        // The second attempt re-drew page one into a NEW accumulator: "one|two", never "one|one|two".
        assertThat(answer.value()).contains("one|two");
        assertThat(completions).hasValue(1);
        assertThat(transport.sent()).isEqualTo(4);
    }

    @Test
    void a_reader_returning_null_instead_of_a_cursor_fails_loudly() {
        RecordedTransport transport = new RecordedTransport(RecordedTransport.Step.answering(200, "page"));
        FeedClient client = FeedClient.of("osv", transport, FeedPolicy.closed());

        assertThatThrownBy(() -> client.fetch(FeedRequest.get(Feeds.ORIGIN), () -> new FeedClient.Reader<String>() {

            @Override
            public Optional<FeedRequest> read(int page, FeedResponse response) {
                return null;
            }

            @Override
            public String complete() {
                return "";
            }
        })).isInstanceOf(FeedException.class).hasMessageContaining("null");
    }
}
