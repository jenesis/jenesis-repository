package build.jenesis.repository.proxy.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.proxy.HttpFetcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An upstream that cannot be reached <em>at all</em> is the contract's transport failure - clause 6's empty
 * {@link Optional} - on every leg, not an exception.
 *
 * <p>{@code HttpFetcherTimeoutTest} covers the stalled upstream, which accepts the connection and then says nothing.
 * This covers the two shapes that never get that far: a refused connection and a host that does not resolve. They
 * are the commonest ways a public mirror is down, and until this was fixed they were the ones the contract could not
 * see - a timeout folded to the empty answer while a refused socket came back as a raw {@link IOException}, which at
 * a catch site is indistinguishable from "this index is malformed".
 *
 * <p><b>Why that gap mattered rather than being untidy.</b> Clause 2's whole apparatus keys on the empty answer:
 * {@code ProxyRelay} splits an ENUMERATION document from a PINNED one and answers {@code 502} rather than letting a
 * local {@code 404} be read by a build as "this package has no versions". A transport handing out an exception
 * instead routes around that split entirely. It is the same defect that once served {@code github.com/pkg/errors} as
 * a module with no versions - that one was a five-second connect timeout, which did reach the split; a connection
 * refused a moment earlier would not have.
 *
 * <p>The bound is deliberate and is asserted here too: only the failure to <em>establish</em> the exchange folds. A
 * body that dies mid-transfer still throws from the read, so a truncated response can never be mistaken for an
 * upstream that was down and cached as though whole.
 */
class HttpFetcherUnreachableTest {

    private final HttpFetcher fetcher = new HttpFetcher(Duration.ofSeconds(10), host -> false);

    @Test
    void a_refused_connection_is_the_empty_answer_on_every_leg() throws IOException {
        URI url = URI.create("http://127.0.0.1:" + closedPort() + "/index.json");

        assertThat(fetcher.fetch(url, Map.of())).as("a refused fetch fails closed, not to an exception").isEmpty();
        assertThat(fetcher.download(url, Map.of())).as("a refused download fails closed").isEmpty();
        assertThat(fetcher.head(url, Map.of())).as("a refused head fails closed").isEmpty();
    }

    @Test
    void a_host_that_does_not_resolve_is_the_empty_answer_on_every_leg() throws IOException {
        // .invalid is reserved by RFC 2606 and can never resolve, so this is a DNS failure by construction.
        URI url = URI.create("http://api.nuget.invalid/v3/index.json");

        assertThat(fetcher.fetch(url, Map.of())).as("an unresolvable fetch fails closed").isEmpty();
        assertThat(fetcher.download(url, Map.of())).as("an unresolvable download fails closed").isEmpty();
        assertThat(fetcher.head(url, Map.of())).as("an unresolvable head fails closed").isEmpty();
    }

    /**
     * The bound: a body that dies mid-transfer is <em>not</em> folded. The upstream here declares a length it does
     * not deliver and then drops the connection, so the failure happens on the read rather than on the connect - and
     * it has to stay visible, because a short body silently becoming "the upstream was down" is how a truncated
     * response gets treated as a complete one.
     */
    @Test
    void a_body_that_dies_mid_transfer_still_throws() throws IOException {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                try (Socket accepted = server.accept(); OutputStream out = accepted.getOutputStream()) {
                    out.write(("HTTP/1.1 200 OK\r\nContent-Length: 4096\r\n\r\nshort").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException _) {
                    // the client's failed read is the assertion; nothing to do on this side
                }
            });
            URI url = URI.create("http://127.0.0.1:" + server.getLocalPort() + "/truncated");
            assertThatThrownBy(() -> fetcher.fetch(url, Map.of()))
                    .as("a truncated body is a read failure, never the empty answer that means 'nobody answered'")
                    .isInstanceOf(IOException.class);
        }
    }

    /** A port nothing is listening on: bound to get a free one from the OS, then released. */
    private static int closedPort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort();
        }
    }
}
