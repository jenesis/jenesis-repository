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
 * {@code closure} renders an answer that leaves its optional parts out - no components, cuts or foreign packages, no
 * source, and a reached version naming no repository, ecosystem or path - as empty rather than failing on them: the
 * server omits what it has none of, and the records the client reads it into say what an omission means.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CliClosureRenderTest {

    private static final String SPARSE = """
            {"repository":"releases","ecosystem":"Maven","coordinate":"org.acme:app","version":"1.0",
             "state":"RESOLVED","resolved":"2026-10-01T00:00:00Z","kind":"RESOLVED","truncated":false,
             "exposure":{"derived":"2026-10-01T00:00:00Z","examined":1,"held":1,"vulnerable":0,
               "reached":[{"coordinate":"org.acme:lib","version":"2.0","held":true,"findings":0}]}}""";

    @TempDir
    Path home;

    private WireMockServer server;

    @BeforeAll
    void setUp() throws Exception {
        server = new WireMockServer(WireMockConfiguration.options().bindAddress("127.0.0.1").dynamicPort());
        server.start();
        server.stubFor(get(urlPathEqualTo("/api/repository/closure")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(SPARSE)));
    }

    @AfterAll
    void tearDown() {
        System.clearProperty("JENREPO_CLI_HOME");
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void an_answer_leaving_its_lists_out_renders_them_as_empty() throws Exception {
        System.setProperty("JENREPO_CLI_HOME", home.toString());
        Cli.run(new String[] {"login", "http://127.0.0.1:" + server.port() + "/", "--key-file",
                Files.writeString(home.resolve("key"), "test-key").toString()});

        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = Cli.run(new String[] {"closure", "releases", "Maven", "org.acme:app", "1.0"});
        } finally {
            System.setOut(original);
        }
        String out = buffer.toString(StandardCharsets.UTF_8);

        assertThat(exit).as(out).isZero();
        assertThat(out).contains("resolved 2026-10-01T00:00:00Z: 0 component(s), 0 unresolved")
                .contains("    org.acme:lib 2.0  held for review")
                .doesNotContain("null");
    }
}
