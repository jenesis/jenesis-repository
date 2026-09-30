package build.jenesis.repository.cli.test;

import module java.base;
import module org.junit.jupiter.api;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import build.jenesis.repository.cli.Cli;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A setting read only as the server starts is written like any other, and the CLI says the change waits for the
 * restart - on the server's word, {@code Jenesis-Applies-On: restart}, so the note cannot disagree with the catalogue.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CliSettingRestartTest {

    @TempDir
    private static Path home;

    private static WireMockServer server;

    @BeforeAll
    public void setUp() throws Exception {
        server = new WireMockServer(WireMockConfiguration.options().bindAddress("127.0.0.1").dynamicPort());
        server.start();
        server.stubFor(put(urlPathEqualTo("/api/settings/osv")).willReturn(aResponse().withStatus(200)
                .withHeader("Jenesis-Applies-On", "restart")));
        server.stubFor(put(urlPathEqualTo("/api/settings/license-unknown")).willReturn(aResponse().withStatus(200)
                .withHeader("Jenesis-Applies-On", "now")));
        System.setProperty("JENREPO_CLI_HOME", home.toString());
        Cli.run(new String[] {"login", "http://127.0.0.1:" + server.port() + "/", "--key", "k"});
    }

    @AfterAll
    public void tearDown() {
        System.clearProperty("JENREPO_CLI_HOME");
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void a_write_that_waits_for_a_restart_says_so() throws Exception {
        assertThat(capture(() -> Cli.run(new String[] {"settings", "set", "osv", "true"})))
                .contains("Set osv").contains("next restarts");
    }

    @Test
    void a_write_that_applies_at_once_says_nothing_more() throws Exception {
        assertThat(capture(() -> Cli.run(new String[] {"settings", "set", "license-unknown", "ALLOW"})))
                .contains("Set license-unknown").doesNotContain("restart");
    }

    private interface Body {
        void run() throws Exception;
    }

    private static String capture(Body body) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
