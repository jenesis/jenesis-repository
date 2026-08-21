package build.jenesis.repository.feed.test;

import build.jenesis.repository.feed.FeedClient;
import build.jenesis.repository.feed.FeedException;
import build.jenesis.repository.feed.FeedPolicy;
import build.jenesis.repository.feed.FeedRequest;
import build.jenesis.repository.feed.FeedResponse;
import build.jenesis.repository.feed.FeedTransport;

import module java.base;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shared single-response reader: the twelve lines every feed whose answer is ONE document wrote for
 * itself - hold a field, parse into it, return no cursor, hand the field back - now owned by the client, with only
 * the vendor's parse left outside it.
 *
 * <p>What is asserted here is not that the twelve lines exist but that they are the <em>right</em> twelve: the parse
 * is handed the live response stream rather than its bytes (so the client's byte cap still bounds the read and a
 * parse that needs a prefix pays for a prefix), a fresh reader is built per attempt (so a retry cannot inherit the
 * previous attempt's half-parsed state), and no page is ever drawn after the first.
 */
class FeedDocumentReaderTest {

    /** A transport answering one scripted body, and recording how many requests it was sent. */
    private static final class OneBody implements FeedTransport {

        private final String body;
        private int sent;

        private OneBody(String body) {
            this.body = body;
        }

        @Override
        public FeedResponse send(FeedRequest request, Duration timeout) {
            sent++;
            return FeedResponse.of(200, body);
        }
    }

    @Test
    void a_single_response_feed_answers_what_its_parse_read_and_draws_no_second_page() throws Exception {
        OneBody transport = new OneBody("{\"status\":1,\"data\":\"ok\"}");
        FeedClient client = FeedClient.of("mend", transport, FeedPolicy.closed());

        FeedClient.Answer<String> answer = client.fetch(FeedRequest.get(Feeds.ORIGIN),
                FeedClient.Reader.document(FeedDocumentReaderTest::text));

        assertThat(answer.status()).isEqualTo(FeedClient.Status.FETCHED);
        assertThat(answer.value()).contains("{\"status\":1,\"data\":\"ok\"}");
        assertThat(transport.sent)
                .as("one response IS the whole answer - the reader advertises no cursor, so the client asks once")
                .isEqualTo(1);
    }

    @Test
    void the_parse_reads_from_the_stream_rather_than_from_the_body_materialised_for_it() throws Exception {
        // The discriminating case, and the reason there is no whole-body convenience here (Contract clause 5): the
        // vendor answers a body that never ends, and the parse needs only its first bytes. Streamed, that costs the
        // prefix. Materialised - a readAllBytes inside the shared reader, which is precisely the convenience a feed
        // would otherwise reach for - it costs the response cap and then fails, on a body the feed could have read.
        FeedTransport endless = (request, timeout) -> new FeedResponse(200, Map.of(), new InputStream() {
            @Override
            public int read() {
                return 'a';
            }
        });
        FeedClient client = FeedClient.of("socket", endless, FeedPolicy.closed());

        FeedClient.Answer<String> answer = client.fetch(FeedRequest.get(Feeds.ORIGIN),
                FeedClient.Reader.document(body -> new String(body.readNBytes(8), StandardCharsets.UTF_8)));

        assertThat(answer.value()).contains("aaaaaaaa");
    }

    @Test
    void the_response_cap_still_bounds_a_parse_that_reads_the_whole_body() {
        FeedTransport endless = (request, timeout) -> new FeedResponse(200, Map.of(), new InputStream() {
            @Override
            public int read() {
                return 'a';
            }
        });
        FeedClient client = FeedClient.of("socket", endless, FeedPolicy.closed().maxResponseBytes(64));

        // A parse that keeps reading is stopped by the same cap that bounds every other reader - the shared reader
        // interposes nothing and exempts nothing, so a vendor cannot spend the heap by answering forever.
        assertThatThrownBy(() -> client.fetch(FeedRequest.get(Feeds.ORIGIN),
                FeedClient.Reader.document(body -> new String(body.readAllBytes(), StandardCharsets.UTF_8))))
                .isInstanceOf(FeedException.class)
                .satisfies(failure -> assertThat(((FeedException) failure).reason())
                        .isEqualTo(FeedException.Reason.RESPONSE_CAP));
    }

    @Test
    void every_attempt_gets_its_own_reader_and_the_answer_is_the_attempt_that_completed() throws Exception {
        // Why the factory answers a Supplier rather than a reader: the client's fresh-accumulator-per-attempt rule is
        // the structural half of "bounds fail visibly", and a shared reader handed to it would be the way around it.
        Supplier<FeedClient.Reader<String>> reader = FeedClient.Reader.document(FeedDocumentReaderTest::text);
        assertThat(reader.get())
                .as("no two attempts can be handed the same reader, so none can inherit another's state")
                .isNotSameAs(reader.get());

        RecordedTransport transport = new RecordedTransport(
                RecordedTransport.Step.answering(500, "first"),
                RecordedTransport.Step.answering(200, "second"));
        Feeds.RecordingPause pause = new Feeds.RecordingPause();
        FeedClient client = FeedClient.of("vulncheck", transport, FeedPolicy.closed(), Clock.systemUTC(), pause);

        FeedClient.Answer<String> answer = client.fetch(FeedRequest.get(Feeds.ORIGIN), reader);

        assertThat(answer.value())
                .as("the retried fetch answers the attempt that completed, never a mixture of the two")
                .contains("second");
        assertThat(transport.sent()).isEqualTo(2);
    }

    @Test
    void a_parse_that_fails_is_the_named_malformed_failure_rather_than_a_half_read_answer() {
        OneBody transport = new OneBody("not the document this feed expects");
        FeedClient client = FeedClient.of("scorecard", transport, FeedPolicy.closed());

        assertThatThrownBy(() -> client.fetch(FeedRequest.get(Feeds.ORIGIN),
                FeedClient.Reader.document(_ -> {
                    throw new IOException("unparseable");
                })))
                .isInstanceOf(FeedException.class)
                .satisfies(failure -> assertThat(((FeedException) failure).reason())
                        .isEqualTo(FeedException.Reason.MALFORMED));
    }

    private static String text(InputStream body) throws IOException {
        return new String(body.readAllBytes(), StandardCharsets.UTF_8);
    }
}
