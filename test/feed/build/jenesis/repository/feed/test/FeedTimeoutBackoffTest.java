package build.jenesis.repository.feed.test;

import module java.base;

import build.jenesis.repository.feed.FeedClient;
import build.jenesis.repository.feed.FeedException;
import build.jenesis.repository.feed.FeedPolicy;
import build.jenesis.repository.feed.FeedRequest;
import build.jenesis.repository.feed.FeedTransport;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The timeout and backoff behaviour, asserted exactly - injected clock and pause, so nothing is slept through. */
class FeedTimeoutBackoffTest {

    private final AtomicInteger completions = new AtomicInteger();
    private final Feeds.TestClock clock = new Feeds.TestClock();

    @Test
    void allows_each_request_the_policy_timeout() throws Exception {
        RecordedTransport transport = new RecordedTransport(RecordedTransport.Step.answering(200, "page"));
        FeedClient client = FeedClient.of("osv", transport,
                FeedPolicy.closed().requestTimeout(Duration.ofSeconds(7)), clock, new Feeds.RecordingPause());

        client.fetch(FeedRequest.get(Feeds.ORIGIN), Feeds.CollectingReader.once(completions));

        assertThat(transport.timeouts()).containsExactly(Duration.ofSeconds(7));
    }

    @Test
    void narrows_a_request_timeout_to_what_is_left_of_the_fetch_deadline() throws Exception {
        RecordedTransport transport = new RecordedTransport(RecordedTransport.Step.answering(200, "page"));
        FeedClient client = FeedClient.of("osv", transport, FeedPolicy.closed()
                .requestTimeout(Duration.ofSeconds(30))
                .deadline(Duration.ofSeconds(40)), clock, new Feeds.RecordingPause());

        client.fetch(FeedRequest.get(Feeds.ORIGIN), () -> new Feeds.CollectingReader(completions, page -> {
            clock.advance(Duration.ofSeconds(25));      // the first page burned 25 s of the 40 s budget
            return page < 2 ? Optional.of(FeedRequest.get(Feeds.ORIGIN)) : Optional.empty();
        }));

        assertThat(transport.timeouts()).containsExactly(Duration.ofSeconds(30), Duration.ofSeconds(15));
    }

    @Test
    void a_fetch_that_runs_past_its_deadline_fails_by_name() {
        RecordedTransport transport = new RecordedTransport(RecordedTransport.Step.answering(200, "page"));
        FeedClient client = FeedClient.of("osv", transport, FeedPolicy.closed()
                .requestTimeout(Duration.ofSeconds(30))
                .deadline(Duration.ofMinutes(1)), clock, new Feeds.RecordingPause());

        assertThatThrownBy(() -> client.fetch(FeedRequest.get(Feeds.ORIGIN),
                () -> new Feeds.CollectingReader(completions, _ -> {
                    clock.advance(Duration.ofSeconds(31));
                    return Optional.of(FeedRequest.get(Feeds.ORIGIN));
                })))
                .isInstanceOf(FeedException.class)
                .extracting(failure -> ((FeedException) failure).reason())
                .isEqualTo(FeedException.Reason.DEADLINE);
        assertThat(completions).hasValue(0);
    }

    @Test
    void retries_a_transport_failure_on_an_exponential_schedule() throws Exception {
        Feeds.RecordingPause pause = new Feeds.RecordingPause(clock);
        RecordedTransport transport = new RecordedTransport(
                RecordedTransport.Step.failing(new IOException("connection reset")),
                RecordedTransport.Step.failing(new IOException("connection reset")),
                RecordedTransport.Step.answering(200, "page"));
        FeedClient client = FeedClient.of("osv", transport, FeedPolicy.closed()
                .maxAttempts(3)
                .backoff(Duration.ofSeconds(1))
                .backoffMultiplier(2)
                .maxBackoff(Duration.ofSeconds(30)), clock, pause);

        FeedClient.Answer<String> answer = client.fetch(FeedRequest.get(Feeds.ORIGIN),
                Feeds.CollectingReader.once(completions));

        assertThat(answer.status()).isEqualTo(FeedClient.Status.FETCHED);
        assertThat(pause.waited()).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void caps_the_backoff_at_the_policy_maximum() {
        FeedPolicy policy = FeedPolicy.closed()
                .backoff(Duration.ofSeconds(1))
                .backoffMultiplier(10)
                .maxBackoff(Duration.ofSeconds(20));

        assertThat(policy.delayBefore(2, Optional.empty())).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.delayBefore(3, Optional.empty())).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.delayBefore(4, Optional.empty())).isEqualTo(Duration.ofSeconds(20));
        assertThat(policy.delayBefore(9, Optional.empty())).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void obeys_a_rate_limits_retry_after_in_preference_to_guessing() throws Exception {
        Feeds.RecordingPause pause = new Feeds.RecordingPause(clock);
        RecordedTransport transport = new RecordedTransport(
                RecordedTransport.Step.answering(429, "slow down", Map.of("Retry-After", List.of("12"))),
                RecordedTransport.Step.answering(200, "page"));
        FeedClient client = FeedClient.of("github", transport, FeedPolicy.closed()
                .maxAttempts(2)
                .backoff(Duration.ofSeconds(1))
                .maxBackoff(Duration.ofMinutes(1)), clock, pause);

        FeedClient.Answer<String> answer = client.fetch(FeedRequest.get(Feeds.ORIGIN),
                Feeds.CollectingReader.once(completions));

        assertThat(answer.status()).isEqualTo(FeedClient.Status.FETCHED);
        assertThat(pause.waited()).containsExactly(Duration.ofSeconds(12));
    }

    @Test
    void never_retries_a_rejected_credential() {
        Feeds.RecordingPause pause = new Feeds.RecordingPause(clock);
        RecordedTransport transport = new RecordedTransport(RecordedTransport.Step.answering(401, "denied"));
        FeedClient client = FeedClient.of("snyk", transport, FeedPolicy.closed().maxAttempts(5), clock, pause);

        assertThatThrownBy(() -> client.fetch(FeedRequest.get(Feeds.ORIGIN),
                Feeds.CollectingReader.once(completions)))
                .isInstanceOf(FeedException.class);

        assertThat(transport.sent()).isEqualTo(1);          // a 401 will not fix itself; retrying only burns quota
        assertThat(pause.waited()).isEmpty();
    }

    @Test
    void gives_up_after_the_attempt_budget_naming_the_attempts_spent() {
        Feeds.RecordingPause pause = new Feeds.RecordingPause(clock);
        RecordedTransport transport = new RecordedTransport(
                RecordedTransport.Step.failing(new IOException("connection reset")));
        FeedClient client = FeedClient.of("osv", transport, FeedPolicy.closed().maxAttempts(3), clock, pause);

        assertThatThrownBy(() -> client.fetch(FeedRequest.get(Feeds.ORIGIN),
                Feeds.CollectingReader.once(completions)))
                .isInstanceOf(FeedException.class)
                .hasMessageContaining("after 3 attempts")
                .extracting(failure -> ((FeedException) failure).attempts())
                .isEqualTo(3);
        assertThat(transport.sent()).isEqualTo(3);
    }

    @Test
    void refuses_a_backoff_that_would_run_past_the_deadline() {
        Feeds.RecordingPause pause = new Feeds.RecordingPause(clock);
        RecordedTransport transport = new RecordedTransport(
                RecordedTransport.Step.failing(new IOException("connection reset")));
        FeedClient client = FeedClient.of("osv", transport, FeedPolicy.closed()
                .maxAttempts(5)
                .requestTimeout(Duration.ofSeconds(5))
                .deadline(Duration.ofSeconds(10))
                .backoff(Duration.ofSeconds(30)), clock, pause);

        assertThatThrownBy(() -> client.fetch(FeedRequest.get(Feeds.ORIGIN),
                Feeds.CollectingReader.once(completions)))
                .isInstanceOf(FeedException.class)
                .extracting(failure -> ((FeedException) failure).reason())
                .isEqualTo(FeedException.Reason.DEADLINE);
        assertThat(pause.waited()).isEmpty();
    }

    @Test
    void an_interrupt_ends_the_fetch_and_restores_the_flag() {
        RecordedTransport transport = new RecordedTransport(
                RecordedTransport.Step.failing(new IOException("connection reset")));
        FeedClient client = FeedClient.of("osv", transport, FeedPolicy.closed().maxAttempts(3), clock,
                new Feeds.InterruptingPause());

        try {
            assertThatThrownBy(() -> client.fetch(FeedRequest.get(Feeds.ORIGIN),
                    Feeds.CollectingReader.once(completions)))
                    .isInstanceOf(FeedException.class)
                    .extracting(failure -> ((FeedException) failure).reason())
                    .isEqualTo(FeedException.Reason.INTERRUPTED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();                           // leave the shared test thread clean
        }
    }

    @Test
    void a_cancelled_request_is_not_mistaken_for_a_transport_failure() {
        AtomicInteger sends = new AtomicInteger();
        // Exactly what a cancelled JDK send does: restore the thread's interrupt flag, then raise interrupted IO.
        FeedTransport cancelling = (request, timeout) -> {
            sends.incrementAndGet();
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("cancelled");
        };
        FeedClient client = FeedClient.of("osv", cancelling, FeedPolicy.closed().maxAttempts(3), clock,
                new Feeds.RecordingPause());

        try {
            assertThatThrownBy(() -> client.fetch(FeedRequest.get(Feeds.ORIGIN),
                    Feeds.CollectingReader.once(completions)))
                    .isInstanceOf(FeedException.class)
                    .extracting(failure -> ((FeedException) failure).reason())
                    .isEqualTo(FeedException.Reason.INTERRUPTED);
            assertThat(sends).hasValue(1);                  // a cancellation is never retried
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void a_read_timeout_is_retried_even_though_it_is_an_interrupted_io_exception() throws Exception {
        Feeds.RecordingPause pause = new Feeds.RecordingPause(clock);
        RecordedTransport transport = new RecordedTransport(
                RecordedTransport.Step.failing(new SocketTimeoutException("read timed out")),
                RecordedTransport.Step.answering(200, "page"));
        FeedClient client = FeedClient.of("osv", transport, FeedPolicy.closed().maxAttempts(2), clock, pause);

        // A SocketTimeoutException IS an InterruptedIOException that nobody interrupted; the thread's flag decides.
        FeedClient.Answer<String> answer = client.fetch(FeedRequest.get(Feeds.ORIGIN),
                Feeds.CollectingReader.once(completions));

        assertThat(answer.status()).isEqualTo(FeedClient.Status.FETCHED);
        assertThat(transport.sent()).isEqualTo(2);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

}
