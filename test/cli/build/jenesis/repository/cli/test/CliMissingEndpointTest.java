package build.jenesis.repository.cli.test;

import module java.base;
import module org.junit.jupiter.api;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import build.jenesis.repository.cli.Cli;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A missing feature is told apart from a missing thing, and said out loud - on the server's word.
 *
 * <p>The product is assembled from modules a deployment may leave out, so "the server answered 404" has two unrelated
 * causes, and the sensible response to them differs completely: one is worth retrying with other arguments and the
 * other never is. Only the server knows which routes it has, and it marks a {@code 404} no route answered with
 * {@code Jenesis-Installed: false}; the CLI answers that with its own exit code and anything else as a refusal.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CliMissingEndpointTest {

    @TempDir
    private static Path home;

    private static WireMockServer server;

    @BeforeAll
    public void setUp() throws Exception {
        server = new WireMockServer(WireMockConfiguration.options().bindAddress("127.0.0.1").dynamicPort());
        server.start();
        System.setProperty("JENREPO_CLI_HOME", home.toString());
        Cli.run(new String[] {"login", "http://127.0.0.1:" + server.port() + "/", "--key-file",
                Files.writeString(home.resolve("key"), "k").toString()});
    }

    @AfterAll
    public void tearDown() {
        System.clearProperty("JENREPO_CLI_HOME");
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void a_route_the_server_does_not_have_is_reported_as_not_installed() throws Exception {
        server.stubFor(get(urlPathEqualTo("/api/scans")).willReturn(aResponse().withStatus(404)
                .withHeader("Jenesis-Installed", "false")));

        String err = captureErr(() -> assertThat(Cli.run(new String[] {"scans"}))
                .as("a capability this deployment does not carry has its own exit code, so a program can stop"
                        + " rather than retry a request that was never going to work")
                .isEqualTo(3));

        assertThat(err).contains("'scans' is not served by this deployment").contains("capabilities");
    }

    @Test
    void a_404_from_a_route_the_server_has_is_a_refusal() {
        server.stubFor(get(urlPathEqualTo("/api/scans")).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> Cli.run(new String[] {"scans"}))
                .as("the main entry turns this into exit code 1 and prints the message")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 404");
    }

    @Test
    void a_failure_the_server_did_not_mean_names_its_reference() {
        server.stubFor(get(urlPathEqualTo("/api/scans")).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/problem+json")
                .withBody("{\"type\":\"about:blank\",\"title\":\"Something went wrong on the server.\","
                        + "\"status\":500,\"reference\":\"4mqg6m86dkqr\",\"instance\":\"/api/scans\"}")));

        assertThatThrownBy(() -> Cli.run(new String[] {"scans"}))
                .as("the message carries what an operator searches the log for")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 500")
                .hasMessageContaining("Reference: 4mqg6m86dkqr");
    }

    private interface Body {
        void run() throws Exception;
    }

    private static String captureErr(Body body) throws Exception {
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setErr(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
