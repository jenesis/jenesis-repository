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
 * {@code findings} prints what a finding's source said beneath it, a line each - who published it, each rating with
 * its vector, the other identifiers it goes by, its weaknesses and its dates - and a row whose source said nothing more
 * prints no such lines.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CliFindingDetailRenderTest {

    private static final String FINDINGS = """
            {"available":true,"next":null,"findings":[
              {"ecosystem":"Maven","coordinate":"org.acme:lib","version":"1.0","id":"CVE-2026-4242","source":"osv",
               "kind":"vulnerability","category":"advisory","severity":"CRITICAL","confidence":1.0,
               "description":"detailed","references":[],"attributes":{},"firstSeen":"2026-01-01T00:00:00Z",
               "lastSeen":"2026-01-01T00:00:00Z","labels":[],
               "vulnerability":{"id":"CVE-2026-4242",
                 "source":{"name":"NVD","url":"https://nvd.nist.gov/vuln/detail/CVE-2026-4242"},
                 "references":[{"id":"GHSA-aaaa-bbbb-cccc","source":{"name":"GitHub Advisory Database"}}],
                 "ratings":[{"source":{"name":"NVD"},"score":9.8,"severity":"critical","method":"CVSSv31",
                             "vector":"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"}],
                 "cwes":[79],"published":"2026-01-02T00:00:00Z"}},
              {"ecosystem":"Maven","coordinate":"org.acme:lib","version":"1.0","id":"GHSA-bare","source":"osv",
               "kind":"vulnerability","category":"advisory","severity":"HIGH","confidence":1.0,
               "description":"bare","references":[],"attributes":{},"firstSeen":"2026-01-01T00:00:00Z",
               "lastSeen":"2026-01-01T00:00:00Z","labels":[]}]}""";

    @TempDir
    Path home;

    private WireMockServer server;

    @BeforeAll
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().bindAddress("127.0.0.1").dynamicPort());
        server.start();
        server.stubFor(get(urlPathEqualTo("/api/findings")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(FINDINGS)));
    }

    @AfterAll
    void tearDown() {
        System.clearProperty("JENREPO_CLI_HOME");
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void a_findings_source_detail_prints_beneath_it() throws Exception {
        System.setProperty("JENREPO_CLI_HOME", home.toString());
        Cli.run(new String[] {"login", "http://127.0.0.1:" + server.port() + "/", "--key-file",
                Files.writeString(home.resolve("key"), "test-key").toString()});

        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = Cli.run(new String[] {"findings", "releases"});
        } finally {
            System.setOut(original);
        }
        String out = buffer.toString(StandardCharsets.UTF_8);

        assertThat(exit).as(out).isZero();
        assertThat(out).contains("    published by NVD https://nvd.nist.gov/vuln/detail/CVE-2026-4242")
                .contains("    rated NVD CVSSv31 9.8 critical CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H")
                .contains("    also GHSA-aaaa-bbbb-cccc (GitHub Advisory Database)")
                .contains("    weakness CWE-79")
                .contains("    published 2026-01-02T00:00:00Z");
        assertThat(out.substring(out.indexOf("GHSA-bare"))).as("a row its source said nothing more of")
                .doesNotContain("published by").doesNotContain("rated ");
    }
}
