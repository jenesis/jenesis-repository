package build.jenesis.repository.net.http.test;

import module java.base;
import module java.net.http;
import module org.junit.jupiter.api;
import build.jenesis.repository.net.PrivateHosts;
import build.jenesis.repository.net.http.ScreenedHttpClient;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScreenedHttpClientTest {

    private HttpServer server;
    private HttpServer other;
    private final List<Headers> received = new CopyOnWriteArrayList<>();
    private final List<byte[]> bodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = serve();
        other = serve();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        other.stop(0);
    }

    @Test
    void a_request_names_the_product_and_nothing_about_the_runtime() throws Exception {
        HttpResponse<String> response = ScreenedHttpClient.newHttpClient().send(
                HttpRequest.newBuilder(url(server, "/echo")).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        Headers headers = received.getLast();
        assertThat(headers.getFirst("User-Agent")).isEqualTo(ScreenedHttpClient.USER_AGENT);
        assertThat(headers.keySet()).map(name -> name.toLowerCase(Locale.ROOT))
                .doesNotContain("accept-encoding", "upgrade", "http2-settings");
        assertThat(headers.values().stream().flatMap(List::stream))
                .noneMatch(value -> value.toLowerCase(Locale.ROOT).contains("java")
                        || value.toLowerCase(Locale.ROOT).contains("jetty"));
    }

    @Test
    void a_caller_naming_its_own_user_agent_is_heard() throws Exception {
        ScreenedHttpClient.newHttpClient().send(HttpRequest.newBuilder(url(server, "/echo"))
                .header("User-Agent", "the-caller").GET().build(), HttpResponse.BodyHandlers.discarding());

        assertThat(received.getLast().get("User-Agent")).containsExactly("the-caller");
    }

    @Test
    void a_host_admitted_public_is_not_connected_to_once_it_resolves_to_a_private_address() throws Exception {
        String admitted = "198.51.100.7", unscreened = "198.51.100.8";
        assertThat(PrivateHosts.resolvesToPrivate(admitted)).as("the screen admits it").isFalse();
        // Both names now answer loopback, as a rebound name would.
        HttpClient client = ScreenedHttpClient.newBuilder()
                .resolver(_ -> List.of(InetAddress.getLoopbackAddress()))
                .build();

        assertThatThrownBy(() -> client.send(HttpRequest.newBuilder(
                        URI.create("http://" + admitted + ":" + server.getAddress().getPort() + "/echo")).build(),
                HttpResponse.BodyHandlers.discarding()))
                .isInstanceOf(ScreenedHttpClient.RebindingRefused.class)
                .hasMessageContaining(admitted);
        assertThat(client.send(HttpRequest.newBuilder(
                        URI.create("http://" + unscreened + ":" + server.getAddress().getPort() + "/echo")).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode())
                .as("a host no screen admitted connects as it resolves - an operator's internal upstream")
                .isEqualTo(200);
    }

    @Test
    void a_body_goes_with_its_declared_length_and_streams_without_one() throws Exception {
        HttpClient client = ScreenedHttpClient.newHttpClient();
        byte[] payload = "x".repeat(100_000).getBytes(StandardCharsets.UTF_8);

        client.send(HttpRequest.newBuilder(url(server, "/echo"))
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(received.getLast().getFirst("Content-Length")).isEqualTo("100000");
        assertThat(bodies.getLast()).isEqualTo(payload);

        client.send(HttpRequest.newBuilder(url(server, "/echo"))
                .PUT(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(payload))).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(received.getLast().getFirst("Transfer-Encoding")).isEqualTo("chunked");
        assertThat(bodies.getLast()).isEqualTo(payload);

        client.send(HttpRequest.newBuilder(url(server, "/echo")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(received.getLast().getFirst("Content-Length")).isEqualTo("0");
    }

    @Test
    void a_body_names_no_content_type_its_caller_did_not() throws Exception {
        HttpClient client = ScreenedHttpClient.newHttpClient();

        client.send(HttpRequest.newBuilder(url(server, "/echo")).PUT(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(received.getLast().containsKey("Content-Type")).isFalse();
        client.send(HttpRequest.newBuilder(url(server, "/echo"))
                .POST(HttpRequest.BodyPublishers.ofString("x", StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(received.getLast().containsKey("Content-Type")).isFalse();
    }

    @Test
    void an_unauthorized_answer_without_a_challenge_is_handed_over() throws Exception {
        HttpResponse<String> response = ScreenedHttpClient.newHttpClient().send(
                HttpRequest.newBuilder(url(server, "/unauthorized")).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).isEqualTo("no");
    }

    @Test
    void a_response_body_reaches_every_kind_of_handler_as_it_was_sent() throws Exception {
        HttpClient client = ScreenedHttpClient.newHttpClient();
        HttpRequest large = HttpRequest.newBuilder(url(server, "/large")).build();

        assertThat(client.send(large, HttpResponse.BodyHandlers.ofString()).body()).hasSize(1_000_000);
        try (InputStream in = client.send(large, HttpResponse.BodyHandlers.ofInputStream()).body()) {
            assertThat(in.readAllBytes()).hasSize(1_000_000);
        }
        assertThat(client.send(large, HttpResponse.BodyHandlers.ofByteArray()).body()).hasSize(1_000_000);
        HttpResponse<Void> head = client.send(HttpRequest.newBuilder(url(server, "/large"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.headers().firstValue("content-length")).contains("1000000");
    }

    @Test
    void an_encoded_body_is_handed_over_as_encoded() throws Exception {
        HttpResponse<byte[]> response = ScreenedHttpClient.newHttpClient().send(
                HttpRequest.newBuilder(url(server, "/gzip")).build(), HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.headers().firstValue("Content-Encoding")).contains("gzip");
        assertThat(new GZIPInputStream(new ByteArrayInputStream(response.body())).readAllBytes())
                .asString(StandardCharsets.UTF_8).isEqualTo("compressed");
    }

    @Test
    void a_redirect_is_answered_as_the_policy_says_and_a_credential_stays_with_its_origin() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(url(server, "/away?to=" + url(other, "/echo")))
                .header("Authorization", "Bearer secret").build();

        assertThat(ScreenedHttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding())
                .statusCode()).as("never followed by default").isEqualTo(302);

        HttpResponse<String> followed = ScreenedHttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .build().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(followed.statusCode()).isEqualTo(200);
        assertThat(followed.previousResponse()).map(HttpResponse::statusCode).contains(302);
        assertThat(received.getLast().containsKey("Authorization"))
                .as("the credential does not follow the redirect to another origin").isFalse();

        ScreenedHttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build().send(
                HttpRequest.newBuilder(url(server, "/away?to=" + url(server, "/echo")))
                        .header("Authorization", "Bearer secret").build(), HttpResponse.BodyHandlers.discarding());
        assertThat(received.getLast().getFirst("Authorization")).as("and stays within its own origin")
                .isEqualTo("Bearer secret");
    }

    @Test
    void a_response_that_does_not_arrive_in_time_is_a_timeout() {
        HttpRequest slow = HttpRequest.newBuilder(url(server, "/slow")).timeout(Duration.ofMillis(200)).build();

        assertThatThrownBy(() -> ScreenedHttpClient.newHttpClient().send(slow, HttpResponse.BodyHandlers.discarding()))
                .isInstanceOf(HttpTimeoutException.class);
    }

    @Test
    void a_port_nothing_listens_on_is_a_refused_connection() throws IOException {
        int closed;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = socket.getLocalPort();
        }

        assertThatThrownBy(() -> ScreenedHttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + closed + "/")).build(),
                HttpResponse.BodyHandlers.discarding())).isInstanceOf(ConnectException.class);
    }

    private HttpServer serve() throws IOException {
        HttpServer created = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        created.createContext("/", this::answer);
        created.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        created.start();
        return created;
    }

    private void answer(HttpExchange exchange) throws IOException {
        received.add(exchange.getRequestHeaders());
        bodies.add(exchange.getRequestBody().readAllBytes());
        String path = exchange.getRequestURI().getPath();
        switch (path) {
            case "/large" -> {
                byte[] body = "y".repeat(1_000_000).getBytes(StandardCharsets.UTF_8);
                if (exchange.getRequestMethod().equals("HEAD")) {
                    exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            }
            case "/gzip" -> {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                    gzip.write("compressed".getBytes(StandardCharsets.UTF_8));
                }
                exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                exchange.sendResponseHeaders(200, bytes.size());
                exchange.getResponseBody().write(bytes.toByteArray());
            }
            case "/unauthorized" -> {
                exchange.sendResponseHeaders(401, 2);
                exchange.getResponseBody().write("no".getBytes(StandardCharsets.UTF_8));
            }
            case "/away" -> {
                String query = exchange.getRequestURI().getQuery();
                exchange.getResponseHeaders().set("Location", query.substring("to=".length()));
                exchange.sendResponseHeaders(302, -1);
            }
            case "/slow" -> {
                try {
                    Thread.sleep(Duration.ofSeconds(3));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                exchange.sendResponseHeaders(200, -1);
            }
            default -> exchange.sendResponseHeaders(200, -1);
        }
        exchange.close();
    }

    private static URI url(HttpServer server, String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }
}
