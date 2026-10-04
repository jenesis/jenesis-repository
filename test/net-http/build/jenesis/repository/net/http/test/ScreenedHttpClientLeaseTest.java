package build.jenesis.repository.net.http.test;

import module java.base;
import module java.net.http;
import module org.junit.jupiter.api;
import build.jenesis.repository.net.http.ScreenedHttpClient;
import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shared clients live as long as a composition holds a lease on them: closing one of two leases leaves them
 * serving, closing the last stops them, and a client built after starts afresh.
 */
class ScreenedHttpClientLeaseTest {

    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpResponse<String> get(HttpClient client) throws IOException, InterruptedException {
        URI url = URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/");
        return client.send(HttpRequest.newBuilder(url).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A client on a connect timeout no other suite uses, so its shared client is this suite's own. */
    private static HttpClient client() {
        return ScreenedHttpClient.newBuilder().connectTimeout(Duration.ofMillis(4_321)).build();
    }

    @Test
    void the_shared_clients_serve_while_a_lease_is_open_and_stop_with_the_last() throws Exception {
        ScreenedHttpClient.Lease first = ScreenedHttpClient.lease();
        ScreenedHttpClient.Lease second = ScreenedHttpClient.lease();
        HttpClient client = client();
        assertThat(get(client).body()).isEqualTo("ok");

        first.close();
        first.close();
        assertThat(get(client).body()).as("one lease still holds them").isEqualTo("ok");

        second.close();
        assertThatThrownBy(() -> get(client)).as("the last lease closed stops the shared client").isNotNull();

        try (ScreenedHttpClient.Lease again = ScreenedHttpClient.lease()) {
            assertThat(get(client()).body()).as("a client built after starts afresh").isEqualTo("ok");
        }
    }
}
