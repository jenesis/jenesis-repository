package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.ProxyRelay;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.gateway.testkit.FormatDrive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

/**
 * The shared {@code blobs}-namespace mechanics exercised through the in-process {@link FormatDrive} doubles (the same
 * {@link FormatDrive.MemStore} and capturing {@link FormatDrive.Call} the format cells use): {@link Blobs#link}'s
 * fail-closed give-up leg after repeated compare-and-set losses (it throws rather than silently dropping a pointer),
 * {@link ProxyRelay#length}'s hand-rolled {@code Content-Length} parse degrade, and every fall-back leg of
 * {@link ProxyRelay#streamFresh} - a transport failure ({@code download == null}), an upstream miss and an upstream
 * non-200/non-304 - under <em>both</em> {@link ProxyRelay.Document} classifications.
 *
 * <p>The two {@code streamFresh} cells that used to live here asserted that a transport failure and a {@code 500} both
 * left the exchange unserved, which is what changed: on a {@link ProxyRelay.Document#ENUMERATION} the local
 * {@code 404} that would then stand is not "the leg served nothing" but the upstream's own answer, so those two
 * outcomes now answer {@code 502}. The claim is not dropped, it is split by classification - the old assertion is
 * exactly what the {@link ProxyRelay.Document#PINNED} cells below still make - and the upstream-miss leg, which nothing
 * asserted before, is added so the {@code 404} that <em>may</em> stand is pinned beside the ones that may not.
 */
class BlobsProxyRelayTest {

    @Test
    void link_fails_closed_after_repeated_compare_and_set_losses() throws IOException {
        // An always-conflicting store: writeVersioned never wins. The bounded CAS retry in link()/write() must give up
        // by throwing IOException, never return as if it published - a lost pointer would leave content unservable.
        Blobs blobs = new Blobs(new AlwaysConflictingStore());
        assertThatIOException()
                .as("write() gives up the CAS loop by throwing, not by silently dropping the pointer")
                .isThrownBy(() -> blobs.write("npm/left-pad/-/left-pad-1.0.0.tgz", "body".getBytes(StandardCharsets.UTF_8)));
        assertThatIOException()
                .as("link() over an already-stored hash gives up the same way")
                .isThrownBy(() -> blobs.link("npm/left-pad/-/left-pad-1.0.0.tgz", "deadbeef"));
    }

    @Test
    void length_degrades_an_absent_or_unparseable_content_length_to_unknown() {
        assertThat(ProxyRelay.length(null)).as("no header streams the body (unknown length)").isEqualTo(-1L);
        assertThat(ProxyRelay.length("123")).as("a numeric header parses").isEqualTo(123L);
        assertThat(ProxyRelay.length(" 456 ")).as("surrounding whitespace is trimmed").isEqualTo(456L);
        assertThat(ProxyRelay.length("not-a-number"))
                .as("an unparseable header degrades to unknown rather than throwing").isEqualTo(-1L);
    }

    @Test
    void a_pinned_relay_still_declines_on_a_transport_failure() throws IOException {
        // The claim the original cell made, kept unchanged for the classification it is still right for: the fetcher
        // yields an empty Optional (an unreachable upstream), and on a version-pinned body the local 404 means "not
        // cached here" - the client re-pulls and nothing about a resolution is decided - so the relay declines and
        // leaves the exchange unwritten.
        FormatDrive.Call exchange = new FormatDrive.Call("GET", "/debian/pool/main/h/hello/hello_1.0.orig.tar.gz");

        boolean served = ProxyRelay.streamFresh(unreachable(), URI.create("https://upstream.example/index"),
                "application/json", exchange, ProxyRelay.Document.PINNED);

        assertThat(served).as("a transport failure is not a serve").isFalse();
        assertThat(exchange.status).as("nothing was written to the exchange").isEqualTo(-1);
    }

    @Test
    void a_pinned_relay_still_declines_on_an_upstream_error_status() throws IOException {
        // The second original claim, likewise: a 500 (any non-200/non-304) on a pinned body declines rather than
        // relaying the error body, leaving the caller's local fallback to answer.
        FormatDrive.Call exchange = new FormatDrive.Call("GET", "/debian/pool/main/h/hello/hello_1.0.orig.tar.gz");

        boolean served = ProxyRelay.streamFresh(answering(500), URI.create("https://upstream.example/index"),
                "application/json", exchange, ProxyRelay.Document.PINNED);

        assertThat(served).as("an upstream non-200 is not a serve").isFalse();
        assertThat(exchange.status).as("the error body was not relayed to the client").isEqualTo(-1);
    }

    @Test
    void an_enumeration_refuses_rather_than_letting_the_local_404_stand() throws IOException {
        //. The same two upstream outcomes over an ENUMERATION: the local 404 that would stand is not "the leg
        // served nothing", it is the upstream's own answer that the enumeration is empty - a fact a build resolves
        // against - so a question this repository could not put to its upstream must not be answered at all.
        for (ProxyFormat.Fetcher fetcher : List.of(unreachable(), answering(503), answering(429), answering(500))) {
            FormatDrive.Call exchange = new FormatDrive.Call("GET", "/npm/left-pad");

            boolean served = ProxyRelay.streamFresh(fetcher, URI.create("https://upstream.example/index"),
                    "application/json", exchange, ProxyRelay.Document.ENUMERATION);

            assertThat(served).as("the 502 IS a serve: false would let the local 404 stand, which is the lie").isTrue();
            assertThat(exchange.status).as("an upstream that did not answer is a bad gateway, not an empty enumeration")
                    .isEqualTo(502);
            assertThat(exchange.body()).as("and no upstream error body is relayed as the enumeration").isEmpty();
        }
    }

    @Test
    void an_upstream_that_answers_no_such_thing_still_lets_the_local_404_stand() throws IOException {
        // The other half of the split, and the reason it is a split rather than a blanket 502: an origin DID answer,
        // and its answer is that it carries no such thing. That is the one shape of absence a client may act on, so it
        // must keep reaching the client as the local 404 - on both classifications, and for 410 as well as 404.
        for (ProxyRelay.Document document : ProxyRelay.Document.values()) {
            for (int status : List.of(404, 410)) {
                FormatDrive.Call exchange = new FormatDrive.Call("GET", "/npm/left-pad");

                boolean served = ProxyRelay.streamFresh(answering(status), URI.create("https://upstream.example/index"),
                        "application/json", exchange, document);

                assertThat(served).as("%s / upstream %s is a real miss, so the leg declines", document, status)
                        .isFalse();
                assertThat(exchange.status).as("nothing was written to the exchange").isEqualTo(-1);
            }
        }
    }

    /** An upstream that is never reached: the SPI's empty-{@link Optional} transport-failure sentinel. */
    private static ProxyFormat.Fetcher unreachable() {
        return (ProxyFormat.Fetcher.Buffered) (url, headers) -> Optional.empty();
    }

    /** An upstream that answers, with {@code status} and a body that must never reach the client. */
    private static ProxyFormat.Fetcher answering(int status) {
        return (ProxyFormat.Fetcher.Buffered) (url, headers) ->
                Optional.of(new ProxyFormat.Fetched(status, "upstream boom".getBytes(StandardCharsets.UTF_8),
                        Map.of()));
    }

    /** A {@link FormatDrive.MemStore} whose compare-and-set never wins, so {@link Blobs#link}'s bounded retry always
     *  exhausts - the store double the CAS-exhaustion leg needs. */
    private static final class AlwaysConflictingStore extends FormatDrive.MemStore {

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) {
            return false;
        }
    }
}
