package build.jenesis.repository.export.test;

import module java.base;
import module jdk.httpserver;
import module org.junit.jupiter.api;
import build.jenesis.repository.export.HttpExportTarget;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.ExportTarget.Credential;
import build.jenesis.repository.format.ExportTarget.Request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The target an export sends through: every request stays under the URL it was given, a redirect is answered rather
 * than followed, and the credential goes the way this product accepts it unless the exporter speaks its client's own
 * header.
 */
class HttpExportTargetTest {

    private static final byte[] ARTIFACT = "the artifact".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private final List<String> requested = new CopyOnWriteArrayList<>();
    private final Map<String, String> authorization = new ConcurrentHashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requested.add(exchange.getRequestMethod() + " " + path);
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            authorization.put(path, header == null ? "" : header);
            exchange.getRequestBody().readAllBytes();
            switch (path) {
                case "/target/moved" -> {
                    exchange.getResponseHeaders().add("Location", "/elsewhere/moved");
                    exchange.sendResponseHeaders(302, -1);
                }
                case "/target/large" -> {
                    byte[] body = new byte[4 * HttpExportTarget.RESPONSE_CAP];
                    Arrays.fill(body, (byte) 'x');
                    exchange.sendResponseHeaders(400, body.length);
                    exchange.getResponseBody().write(body);
                }
                case "/target/present.jar" -> {
                    exchange.sendResponseHeaders(200, ARTIFACT.length);
                    exchange.getResponseBody().write(ARTIFACT);
                }
                case "/target/absent.jar" -> exchange.sendResponseHeaders(404, -1);
                default -> exchange.sendResponseHeaders(201, -1);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void a_path_that_would_leave_the_target_is_refused_before_anything_is_sent() {
        HttpExportTarget target = target(Optional.empty());
        for (String path : List.of("/target/x", "../x", "a/../../x", "a/..", "a/..?x", "http://elsewhere/x",
                "a\\..\\x")) {
            assertThatThrownBy(() -> target.send(Request.get(path)))
                    .as(path).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(requested).isEmpty();
    }

    @Test
    void a_path_resolves_under_the_url_whether_or_not_it_ends_in_a_slash() throws IOException {
        URI url = URI.create("http://" + address() + "/target");
        new HttpExportTarget(url, Optional.empty()).send(put("org/acme/lib.jar"));
        assertThat(requested).containsExactly("PUT /target/org/acme/lib.jar");
    }

    @Test
    void a_redirect_is_answered_and_never_followed() throws IOException {
        ExportTarget.Response response = target(Optional.of(new Credential(Optional.empty(), "secret")))
                .send(put("moved"));
        assertThat(response.status()).isEqualTo(302);
        assertThat(requested).as("neither the bytes nor the credential went where the redirect pointed")
                .containsExactly("PUT /target/moved");
    }

    @Test
    void a_credential_with_a_user_name_goes_as_basic_and_one_without_as_bearer() throws IOException {
        target(Optional.of(new Credential(Optional.of("deployer"), "s3cret"))).send(put("basic"));
        target(Optional.of(new Credential(Optional.empty(), "token"))).send(put("bearer"));
        target(Optional.empty()).send(put("anonymous"));
        assertThat(authorization).containsEntry("/target/basic", "Basic " + Base64.getEncoder()
                        .encodeToString("deployer:s3cret".getBytes(StandardCharsets.UTF_8)))
                .containsEntry("/target/bearer", "Bearer token")
                .containsEntry("/target/anonymous", "");
    }

    @Test
    void an_exporter_speaking_its_clients_own_header_is_not_given_a_second_one() throws IOException {
        HttpExportTarget target = target(Optional.of(new Credential(Optional.empty(), "token")));
        target.send(new Request("PUT", "own", Map.of("Authorization", "Token mine"), ExportTarget.Body.of(ARTIFACT)));
        target.send(new Request("PUT", "nuget", Map.of("X-NuGet-ApiKey", "key"), ExportTarget.Body.of(ARTIFACT)));
        assertThat(authorization).containsEntry("/target/own", "Token mine").containsEntry("/target/nuget", "");
    }

    @Test
    void an_empty_file_is_sent_as_an_empty_body() throws IOException {
        ExportTarget.Response response = target(Optional.empty()).send(Request.put("empty", "application/octet-stream",
                ExportTarget.Body.of(0, () -> new ByteArrayInputStream(new byte[0]))));
        assertThat(response.ok()).isTrue();
        assertThat(requested).containsExactly("PUT /target/empty");
    }

    @Test
    void an_answer_is_kept_only_up_to_the_cap() throws IOException {
        ExportTarget.Response response = target(Optional.empty()).send(put("large"));
        assertThat(response.ok()).isFalse();
        assertThat(response.body()).hasSize(HttpExportTarget.RESPONSE_CAP);
    }

    @Test
    void a_file_the_target_serves_is_hashed_and_one_it_does_not_is_absent() throws IOException, NoSuchAlgorithmException {
        HttpExportTarget target = target(Optional.empty());
        assertThat(target.sha256("present.jar")).contains(HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(ARTIFACT)));
        assertThat(target.sha256("absent.jar")).isEmpty();
    }

    private HttpExportTarget target(Optional<Credential> credential) {
        return new HttpExportTarget(URI.create("http://" + address() + "/target/"), credential);
    }

    private String address() {
        return "127.0.0.1:" + server.getAddress().getPort();
    }

    private static Request put(String path) {
        return Request.put(path, "application/octet-stream", ExportTarget.Body.of(ARTIFACT));
    }
}
