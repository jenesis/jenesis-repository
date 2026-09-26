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
        String admitted = "9.9.9.9", unscreened = "149.112.112.112";
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
    void a_client_named_no_timeouts_still_has_bounded_ones() {
        assertThat(ScreenedHttpClient.newHttpClient().connectTimeout()).contains(ScreenedHttpClient.CONNECT_TIMEOUT);
        assertThat(ScreenedHttpClient.IDLE_TIMEOUT).as("an idle upstream is given up on within minutes, not years")
                .isPositive().isLessThanOrEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void a_server_that_accepts_and_never_answers_fails_the_call_by_name() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            List<Socket> held = new CopyOnWriteArrayList<>();
            Thread.ofVirtual().start(() -> {
                try {
                    held.add(silent.accept());
                } catch (IOException _) {
                    // the server socket closes with the test
                }
            });
            HttpClient client = ScreenedHttpClient.newBuilder().idleTimeout(Duration.ofMillis(500)).build();
            long started = System.nanoTime();

            assertThatThrownBy(() -> client.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + silent.getLocalPort() + "/never")).build(),
                    HttpResponse.BodyHandlers.discarding()))
                    .isInstanceOf(HttpTimeoutException.class)
                    .hasMessageContaining("127.0.0.1:" + silent.getLocalPort())
                    .hasMessageContaining("idle timeout");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).as("given up on at the idle timeout")
                    .isLessThan(Duration.ofSeconds(10));
            assertThat(held).as("the connection was made; it is the answer that never came").hasSize(1);
            for (Socket socket : held) {
                socket.close();
            }
        }
    }

    @Test
    void a_request_naming_a_longer_wait_than_the_idle_timeout_is_waited_for() throws Exception {
        HttpClient client = ScreenedHttpClient.newBuilder().idleTimeout(Duration.ofMillis(300)).build();

        assertThat(client.send(HttpRequest.newBuilder(url(server, "/slow")).timeout(Duration.ofSeconds(20)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode())
                .as("a model or a portal thinking for longer than the idle timeout, as its caller said it may")
                .isEqualTo(200);
    }

    @Test
    void a_body_that_stops_half_way_fails_by_name() throws Exception {
        try (ServerSocket halting = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                try (Socket socket = halting.accept()) {
                    socket.getInputStream().read(new byte[8192]);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n0123456789")
                            .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    Thread.sleep(Duration.ofSeconds(30));
                } catch (IOException | InterruptedException _) {
                    // the client abandons the connection, which is what is being waited for
                }
            });
            HttpClient client = ScreenedHttpClient.newBuilder().idleTimeout(Duration.ofMillis(500)).build();

            assertThatThrownBy(() -> client.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + halting.getLocalPort() + "/half")).build(),
                    HttpResponse.BodyHandlers.ofString()))
                    .isInstanceOf(HttpTimeoutException.class)
                    .hasMessageContaining("127.0.0.1:" + halting.getLocalPort());
        }
    }

    @Test
    void an_exchange_that_keeps_moving_outlasts_the_idle_timeout() throws Exception {
        HttpClient client = ScreenedHttpClient.newBuilder().idleTimeout(Duration.ofMillis(400)).build();
        InputStream trickle = new InputStream() {
            private int sent;

            @Override
            public int read() {
                throw new UnsupportedOperationException();
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                if (sent == 12) {
                    return -1;
                }
                try {
                    Thread.sleep(Duration.ofMillis(100));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                buffer[offset] = (byte) ('a' + sent++);
                return 1;
            }
        };

        HttpResponse<Void> uploaded = client.send(HttpRequest.newBuilder(url(server, "/echo"))
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> trickle)).build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(uploaded.statusCode()).as("an upload three times the idle timeout long").isEqualTo(200);
        assertThat(bodies.getLast()).asString(StandardCharsets.UTF_8).isEqualTo("abcdefghijkl");

        HttpResponse<String> downloaded = client.send(HttpRequest.newBuilder(url(server, "/trickle")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(downloaded.body()).as("a download three times the idle timeout long").isEqualTo("abcdefghijkl");
    }

    @Test
    void a_connect_nobody_accepts_times_out_by_name() throws Exception {
        try (ServerSocket full = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            List<Socket> queued = new ArrayList<>();
            try {
                // Fill the accept queue, which is never drained, until a connect is left waiting.
                boolean waiting = false;
                for (int attempt = 0; attempt < 16 && !waiting; attempt++) {
                    Socket socket = new Socket();
                    queued.add(socket);
                    try {
                        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), full.getLocalPort()),
                                200);
                    } catch (SocketTimeoutException _) {
                        waiting = true;
                    }
                }
                Assumptions.assumeTrue(waiting, "this platform accepts connects past a full backlog");
                HttpClient client = ScreenedHttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();

                assertThatThrownBy(() -> client.send(HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + full.getLocalPort() + "/")).build(),
                        HttpResponse.BodyHandlers.discarding()))
                        .isInstanceOf(HttpConnectTimeoutException.class)
                        .hasMessageContaining("127.0.0.1:" + full.getLocalPort());
            } finally {
                for (Socket socket : queued) {
                    socket.close();
                }
            }
        }
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
            case "/trickle" -> {
                exchange.sendResponseHeaders(200, 12);
                for (int sent = 0; sent < 12; sent++) {
                    try {
                        Thread.sleep(Duration.ofMillis(100));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.getResponseBody().write('a' + sent);
                    exchange.getResponseBody().flush();
                }
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
