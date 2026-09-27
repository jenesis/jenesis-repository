package build.jenesis.repository.net.http.test;

import module java.base;
import module java.net.http;
import module org.junit.jupiter.api;
import build.jenesis.repository.net.http.BoundedBody;
import build.jenesis.repository.net.http.ScreenedHttpClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A whole-body read stops at the bound its caller named: a peer streaming past it is refused by name at the byte that
 * crosses it rather than read on for as long as it keeps sending, one declaring a longer body is refused before any
 * of it is read, and a body inside the bound arrives whole.
 */
class BoundedBodyTest {

    private static final int BOUND = 64 * 1024;

    /** What the endless peer offers: far past the bound, and small enough that a read ignoring it still ends. */
    private static final long OFFERED = 32L * 1024 * 1024;

    private HttpServer server;
    private final AtomicLong streamed = new AtomicLong();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::answer);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void a_body_streaming_past_the_bound_is_refused_by_name() {
        URI endless = url("/endless");

        assertThatThrownBy(() -> ScreenedHttpClient.newHttpClient().send(HttpRequest.newBuilder(endless).build(),
                BoundedBody.ofByteArray(endless, BOUND)))
                .isInstanceOf(BoundedBody.TooLarge.class)
                .hasMessageContaining(endless.toString())
                .hasMessageContaining(BOUND + "-byte bound");
        assertThat(streamed.get()).as("the peer was stopped near the bound, not read to the end of what it offered")
                .isLessThan(OFFERED);
        assertThatThrownBy(() -> ScreenedHttpClient.newHttpClient().send(HttpRequest.newBuilder(endless).build(),
                BoundedBody.ofString(endless, BOUND)))
                .as("the text form is bounded the same way").isInstanceOf(BoundedBody.TooLarge.class);
    }

    @Test
    void a_body_declaring_a_length_past_the_bound_is_refused_before_it_is_read() {
        URI declared = url("/declared");

        assertThatThrownBy(() -> ScreenedHttpClient.newHttpClient().send(HttpRequest.newBuilder(declared).build(),
                BoundedBody.ofByteArray(declared, BOUND)))
                .isInstanceOf(BoundedBody.TooLarge.class)
                .hasMessageContaining(declared.toString())
                .hasMessageContaining("declares " + (BOUND + 1) + " bytes");
    }

    @Test
    void a_body_inside_the_bound_arrives_whole() throws Exception {
        URI exact = url("/exact");

        assertThat(ScreenedHttpClient.newHttpClient().send(HttpRequest.newBuilder(exact).build(),
                BoundedBody.ofByteArray(exact, BOUND)).body()).hasSize(BOUND).containsOnly((byte) 'z');
        assertThat(ScreenedHttpClient.newHttpClient().send(HttpRequest.newBuilder(url("/text")).build(),
                BoundedBody.ofString(url("/text"), BOUND)).body()).isEqualTo("gr\u00fc\u00dfe");
    }

    private void answer(HttpExchange exchange) throws IOException {
        switch (exchange.getRequestURI().getPath()) {
            case "/endless" -> {
                exchange.sendResponseHeaders(200, 0);
                byte[] chunk = new byte[8192];
                try (OutputStream out = exchange.getResponseBody()) {
                    for (long sent = 0; sent < OFFERED; sent += chunk.length) {
                        out.write(chunk);
                        streamed.addAndGet(chunk.length);
                    }
                } catch (IOException _) {
                    // the client hangs up at the bound, which is what is being waited for
                }
            }
            case "/declared" -> {
                exchange.sendResponseHeaders(200, BOUND + 1);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(new byte[BOUND + 1]);
                } catch (IOException _) {
                    // the client may hang up without reading
                }
            }
            case "/exact" -> {
                byte[] body = new byte[BOUND];
                Arrays.fill(body, (byte) 'z');
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            case "/text" -> {
                byte[] body = "gr\u00fc\u00dfe".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            default -> exchange.sendResponseHeaders(404, -1);
        }
        exchange.close();
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }
}
