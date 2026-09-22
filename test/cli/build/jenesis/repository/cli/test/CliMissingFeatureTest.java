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

/**
 * A 404 is told apart from a missing feature, and said out loud.
 *
 * <p>The product is assembled from modules a deployment may leave out, so "the server answered 404" has two
 * unrelated causes: the feature is not installed, or the thing asked about is not there. The status code is
 * identical and a caller cannot tell them apart - which for a program is the worse of the two, because the sensible
 * response differs completely: one is worth retrying with different arguments and the other never is. Each command
 * declares the module behind it, so the CLI reads {@code /api/capabilities} when a call comes back empty-handed and
 * reports which of the two happened, under its own exit code.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CliMissingFeatureTest {

    private static final String NOT_INSTALLED = """
            {"version":1,"formats":[],"importSources":[],"signals":[],"modules":[
              {"module":"build.jenesis.repository.scans","installed":false,"enableKey":null,
               "enabled":false,"live":false}],
             "features":{"advisories":false,"advisoriesEnabled":false,"staging":false,"retention":false,
               "provenanceEnabled":false,"upstream":false,"tokenExchange":false,"rateLimit":false},
             "scan":false,"provenance":false,"audit":false,"dependents":false,"search":false,
             "walk":false,"gc":false}""";

    private static final String SWITCHED_OFF = """
            {"version":1,"formats":[],"importSources":[],"signals":[],"modules":[
              {"module":"build.jenesis.repository.scans","installed":true,"enableKey":"scans-enabled",
               "enabled":false,"live":true}],
             "features":{"advisories":false,"advisoriesEnabled":false,"staging":false,"retention":false,
               "provenanceEnabled":false,"upstream":false,"tokenExchange":false,"rateLimit":false},
             "scan":false,"provenance":false,"audit":false,"dependents":false,"search":false,
             "walk":false,"gc":false}""";

    @TempDir
    private static Path home;

    private static WireMockServer server;

    @BeforeAll
    public void setUp() throws Exception {
        server = new WireMockServer(WireMockConfiguration.options().bindAddress("127.0.0.1").dynamicPort());
        server.start();
        server.stubFor(get(urlPathEqualTo("/api/scans")).willReturn(aResponse().withStatus(404)));
        System.setProperty("JENREG_CLI_HOME", home.toString());
        Cli.run(new String[] {"login", "http://127.0.0.1:" + server.port() + "/", "--key", "k"});
    }

    @AfterAll
    public void tearDown() {
        System.clearProperty("JENREG_CLI_HOME");
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void an_absent_module_is_reported_as_not_installed() throws Exception {
        server.stubFor(get(urlPathEqualTo("/api/capabilities")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(NOT_INSTALLED)));

        String err = captureErr(() -> assertThat(Cli.run(new String[] {"scans"}))
                .as("a capability this deployment does not carry has its own exit code, so a program can stop"
                        + " rather than retry a request that was never going to work")
                .isEqualTo(3));

        assertThat(err).contains("'scans' is not installed on this server");
        assertThat(err).as("naming the module is what makes it actionable for whoever assembles the deployment")
                .contains("build.jenesis.repository.scans");
        assertThat(err).contains("capabilities");
    }

    @Test
    void an_installed_but_disabled_module_says_which_setting_turns_it_on() throws Exception {
        server.stubFor(get(urlPathEqualTo("/api/capabilities")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(SWITCHED_OFF)));

        String err = captureErr(() -> assertThat(Cli.run(new String[] {"scans"})).isEqualTo(3));

        assertThat(err).contains("installed but switched off");
        assertThat(err).as("the remedy, not just the diagnosis").contains("settings set scans-enabled true");
    }

    @Test
    void a_server_that_cannot_answer_capabilities_still_reports_the_original_failure() throws Exception {
        server.stubFor(get(urlPathEqualTo("/api/capabilities")).willReturn(aResponse().withStatus(500)));

        String err = captureErr(() -> assertThat(Cli.run(new String[] {"scans"}))
                .as("the capabilities read is a best-effort refinement; failing it must not replace the real"
                        + " error with its own")
                .isEqualTo(1));

        assertThat(err).contains("HTTP 404");
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
