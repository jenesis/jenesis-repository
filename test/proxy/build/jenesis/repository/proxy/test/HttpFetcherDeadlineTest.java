package build.jenesis.repository.proxy.test;

import module org.junit.jupiter.api;
import module java.base;
import module java.net.http;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.proxy.HttpFetcher;
import build.jenesis.repository.proxy.ProxySettingsContributor;
import build.jenesis.repository.settings.Setting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The deadline on one upstream fetch is the operator's: the catalogue ships none, a fetch with nothing set runs for
 * as long as its upstream keeps moving, and one with {@code proxy-fetch-deadline} set is abandoned at it, by name,
 * however steadily the upstream trickles. The upstream here sends a byte every tenth of a second - briskly enough that
 * neither the idle timeout nor the throughput floor, judged over a minute, ever ends it.
 */
class HttpFetcherDeadlineTest {

    private static final String PROPERTY = "jenreg." + ProxySettingsContributor.DEADLINE_KEY;

    @AfterEach
    void clear() {
        System.clearProperty(PROPERTY);
    }

    @Test
    void the_catalogue_ships_no_deadline_and_a_fetch_with_none_set_runs_to_its_end() throws Exception {
        Setting declared = new ProxySettingsContributor().settings().stream()
                .filter(setting -> setting.key().equals(ProxySettingsContributor.DEADLINE_KEY))
                .findFirst().orElseThrow();
        assertThat(declared.kind()).isEqualTo(Setting.Kind.DURATION);
        assertThat(Duration.parse(declared.defaultValue())).as("no deadline unless an operator sets one").isZero();
        assertThat(declared.description()).as("and the text states the trade-off an operator is choosing")
                .contains("slow link").contains("throughput floor");

        try (Trickle upstream = new Trickle(20)) {
            assertThat(read(upstream)).as("two seconds of trickle, with no deadline to cut it").hasSize(20);
        }
    }

    @Test
    void a_fetch_past_the_deadline_set_is_abandoned_by_name() throws Exception {
        System.setProperty(PROPERTY, "1s");

        try (Trickle upstream = new Trickle(1000)) {
            long started = System.nanoTime();
            // The body stream reports its end as "closed", carrying the cause that ended it.
            assertThatThrownBy(() -> read(upstream))
                    .isInstanceOf(IOException.class)
                    .rootCause().isInstanceOf(HttpTimeoutException.class)
                    .hasMessageContaining(upstream.url().toString())
                    .hasMessageContaining("deadline of 1000 ms");
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .as("cut at the deadline, not at the hundred seconds the body would take")
                    .isLessThan(Duration.ofSeconds(10));
        }
    }

    private static byte[] read(Trickle upstream) throws IOException {
        ProxyFormat.Download download = new HttpFetcher(Duration.ofSeconds(30), host -> false)
                .download(upstream.url(), Map.of()).orElseThrow();
        try (InputStream body = download.body()) {
            return body.readAllBytes();
        }
    }

    /** An upstream answering one request with {@code length} bytes, one every tenth of a second. */
    private static final class Trickle implements AutoCloseable {

        private final ServerSocket socket;

        Trickle(int length) throws IOException {
            socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread.ofVirtual().start(() -> {
                try (Socket accepted = socket.accept()) {
                    accepted.getInputStream().read(new byte[8192]);
                    OutputStream out = accepted.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + length + "\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    for (int sent = 0; sent < length; sent++) {
                        out.write('x');
                        out.flush();
                        Thread.sleep(Duration.ofMillis(100));
                    }
                } catch (IOException | InterruptedException _) {
                    // the fetcher abandons the connection, which is what is being waited for
                }
            });
        }

        URI url() {
            return URI.create("http://127.0.0.1:" + socket.getLocalPort() + "/trickle");
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
