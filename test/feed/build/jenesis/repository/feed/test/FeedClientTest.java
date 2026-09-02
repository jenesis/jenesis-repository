package build.jenesis.repository.feed.test;

import module java.base;

import build.jenesis.repository.feed.FeedClient;
import build.jenesis.repository.feed.FeedException;
import build.jenesis.repository.feed.FeedPolicy;
import build.jenesis.repository.feed.FeedRequest;
import build.jenesis.repository.feed.FeedResponse;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The request shaping, the status branch and the two fail modes - what every feed used to hand-roll per vendor. */
class FeedClientTest {

    private final AtomicInteger completions = new AtomicInteger();

    @Test
    void draws_a_single_page_and_completes() throws Exception {
        RecordedTransport transport = new RecordedTransport(RecordedTransport.Step.answering(200, "{\"vulns\":[]}"));
        FeedClient client = FeedClient.of("osv", transport, FeedPolicy.closed());

        FeedClient.Answer<String> answer = client.fetch(FeedRequest.get(Feeds.ORIGIN),
                Feeds.CollectingReader.once(completions));

        assertThat(answer.status()).isEqualTo(FeedClient.Status.FETCHED);
        assertThat(answer.value()).contains("{\"vulns\":[]}");
        assertThat(completions).hasValue(1);
        assertThat(transport.sent()).isEqualTo(1);
    }

    @Test
    void carries_the_headers_body_and_method_the_feed_shaped() throws Exception {
        RecordedTransport transport = new RecordedTransport(RecordedTransport.Step.answering(200, "[]"));
        FeedClient client = FeedClient.of("snyk", transport, FeedPolicy.closed());

        client.fetch(FeedRequest.post(Feeds.ORIGIN, "{\"q\":1}", "application/json")
                .bearer("s3cret")
                .header("Accept", "application/vnd.api+json"), Feeds.CollectingReader.once(completions));

        FeedRequest sent = transport.requests().getFirst();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.body()).isEqualTo("{\"q\":1}");
        assertThat(sent.header("authorization")).contains("Bearer s3cret");
        assertThat(sent.header("ACCEPT")).contains("application/vnd.api+json");
        assertThat(sent.header("content-type")).contains("application/json");
    }

    @Test
    void never_renders_a_credential_into_a_message() {
        FeedRequest request = FeedRequest.get(Feeds.ORIGIN).bearer("s3cret")
                .header("X-Api-Key", "k3y")
                .header("Accept", "application/json");

        assertThat(request.toString()).doesNotContain("s3cret").doesNotContain("k3y")
                .contains("<redacted>")
                .contains("application/json");
    }

    @Test
    void basic_authentication_encodes_the_credential() {
        FeedRequest request = FeedRequest.get(Feeds.ORIGIN).basic("token", "");

        assertThat(request.header("Authorization")).contains("Basic "
                + Base64.getEncoder().encodeToString("token:".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void a_non_200_fails_closed_naming_the_status() {
        RecordedTransport transport = new RecordedTransport(RecordedTransport.Step.answering(403, "forbidden"));
        FeedClient client = FeedClient.of("snyk", transport, FeedPolicy.closed());

        assertThatThrownBy(() -> client.fetch(FeedRequest.get(Feeds.ORIGIN),
                Feeds.CollectingReader.once(completions)))
                .isInstanceOf(FeedException.class)
                .hasMessageContaining("snyk")
                .hasMessageContaining("403")
                .extracting(failure -> ((FeedException) failure).reason())
                .isEqualTo(FeedException.Reason.STATUS);
        assertThat(completions).hasValue(0);
    }

    @Test
    void a_non_200_degrades_under_a_soft_policy_without_throwing() throws Exception {
        RecordedTransport transport = new RecordedTransport(RecordedTransport.Step.answering(500, "boom"));
        FeedClient client = FeedClient.of("epss", transport, FeedPolicy.soft().maxAttempts(1));

        FeedClient.Answer<String> answer = client.fetch(FeedRequest.get(Feeds.ORIGIN),
                Feeds.CollectingReader.once(completions));

        assertThat(answer.status()).isEqualTo(FeedClient.Status.DEGRADED);
        assertThat(answer.value()).isEmpty();
        assertThat(answer.failure()).isPresent();
        assertThat(answer.failure().orElseThrow().status()).isEqualTo(500);
        assertThat(answer.orElse("no scores")).isEqualTo("no scores");
    }

    @Test
    void an_unconfigured_feed_self_skips_without_touching_the_network() throws Exception {
        FeedClient client = FeedClient.unconfigured("vulndb", "vulndb-client-id", "vulndb-client-secret");

        FeedClient.Answer<String> answer = client.fetch(FeedRequest.get(Feeds.ORIGIN),
                Feeds.CollectingReader.once(completions));

        assertThat(client.configured()).isFalse();
        assertThat(answer.status()).isEqualTo(FeedClient.Status.SKIPPED);
        assertThat(answer.skipped()).isTrue();
        assertThat(answer.note()).contains("vulndb-client-id").contains("vulndb-client-secret");
        assertThat(answer.value()).isEmpty();
        assertThat(answer.failure()).isEmpty();
    }

    @Test
    void a_skip_is_never_an_empty_answer() throws Exception {
        FeedClient.Answer<String> skipped = FeedClient.unconfigured("mend").fetch(FeedRequest.get(Feeds.ORIGIN),
                Feeds.CollectingReader.once(completions));

        // The record makes "an empty value" and "a skip" different states rather than the same Optional.empty().
        assertThatThrownBy(() -> new FeedClient.Answer<>(FeedClient.Status.FETCHED, Optional.empty(),
                Optional.empty(), ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(skipped.fetched()).isFalse();
    }

    @Test
    void a_reader_that_blows_up_fails_as_malformed_never_as_an_empty_answer() {
        RecordedTransport transport = new RecordedTransport(RecordedTransport.Step.answering(200, "not json"));
        FeedClient client = FeedClient.of("github", transport, FeedPolicy.closed());

        assertThatThrownBy(() -> client.fetch(FeedRequest.get(Feeds.ORIGIN), () -> new FeedClient.Reader<String>() {

            @Override
            public Optional<FeedRequest> read(int page, FeedResponse response) {
                throw new IllegalStateException("unparseable");
            }

            @Override
            public String complete() {
                return "";
            }
        })).isInstanceOf(FeedException.class)
                .extracting(failure -> ((FeedException) failure).reason())
                .isEqualTo(FeedException.Reason.MALFORMED);
    }

    @Test
    void an_over_long_body_fails_at_the_byte_cap() {
        String oversized = "x".repeat(4096);
        RecordedTransport transport = new RecordedTransport(RecordedTransport.Step.answering(200, oversized));
        FeedClient client = FeedClient.of("kev", transport, FeedPolicy.closed().maxResponseBytes(1024));

        assertThatThrownBy(() -> client.fetch(FeedRequest.get(Feeds.ORIGIN),
                Feeds.CollectingReader.once(completions)))
                .isInstanceOf(FeedException.class)
                .hasMessageContaining("1024")
                .extracting(failure -> ((FeedException) failure).reason())
                .isEqualTo(FeedException.Reason.RESPONSE_CAP);
        assertThat(completions).hasValue(0);
    }

    @Test
    void skipped_bytes_count_against_the_body_cap() {
        RecordedTransport transport = new RecordedTransport(RecordedTransport.Step.answering(200, "y".repeat(4096)));
        FeedClient client = FeedClient.of("kev", transport, FeedPolicy.closed().maxResponseBytes(1024));

        // A reader that jumps over the payload still made the feed send it, so the budget is spent either way.
        assertThatThrownBy(() -> client.fetch(FeedRequest.get(Feeds.ORIGIN), () -> new FeedClient.Reader<String>() {

            @Override
            public Optional<FeedRequest> read(int page, FeedResponse response) throws IOException {
                long skipped = 0;
                while (skipped < 4096) {
                    long step = response.body().skip(4096 - skipped);
                    if (step <= 0) {
                        break;
                    }
                    skipped += step;
                }
                return Optional.empty();
            }

            @Override
            public String complete() {
                return "";
            }
        })).isInstanceOf(FeedException.class)
                .extracting(failure -> ((FeedException) failure).reason())
                .isEqualTo(FeedException.Reason.RESPONSE_CAP);
    }

    @Test
    void a_body_under_the_cap_streams_through_untouched() throws Exception {
        RecordedTransport transport = new RecordedTransport(RecordedTransport.Step.answering(200, "z".repeat(1024)));
        FeedClient client = FeedClient.of("kev", transport, FeedPolicy.closed().maxResponseBytes(1024));

        assertThatCode(() -> client.fetch(FeedRequest.get(Feeds.ORIGIN), Feeds.CollectingReader.once(completions)))
                .doesNotThrowAnyException();
        assertThat(completions).hasValue(1);
    }

    @Test
    void a_policy_refuses_a_deadline_shorter_than_one_request() {
        assertThatThrownBy(() -> FeedPolicy.closed()
                .requestTimeout(Duration.ofSeconds(30))
                .deadline(Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_request_needs_an_absolute_uri_with_a_host() {
        assertThatThrownBy(() -> FeedRequest.get(URI.create("/v1/query")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
