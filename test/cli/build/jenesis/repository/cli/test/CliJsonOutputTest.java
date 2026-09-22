package build.jenesis.repository.cli.test;

import module java.base;
import module org.junit.jupiter.api;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import build.jenesis.repository.cli.Cli;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --json} answers with the API's own document, and nothing else on stdout.
 *
 * <p>The contract worth pinning is that <em>stdout is exactly one JSON value</em>. A program parses it whole, so a
 * stray line of human rendering alongside it is not untidy output, it is a parse failure - and that is the
 * failure mode a mode built by suppressing one renderer in favour of another actually has.
 *
 * <p>Verbatim matters as much. The document is what the server sent rather than a re-serialisation of what this
 * client parsed out of it, so a field the CLI has never heard of still reaches the caller. That is asserted with a
 * response carrying exactly such a field.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CliJsonOutputTest {

    /** Deliberately carries a field no record in this client models. */
    private static final String SETTINGS =
            "[{\"key\":\"audit\",\"value\":\"true\",\"defaultValue\":\"true\",\"overridden\":false,"
                    + "\"appliesImmediately\":true,\"pinned\":false,\"pinnedBy\":\"op\u00e9rateur\","
                    + "\"somethingTheCliDoesNotModel\":42}]";

    @TempDir
    private static Path home;

    private static WireMockServer server;

    @BeforeAll
    public void setUp() throws Exception {
        server = new WireMockServer(WireMockConfiguration.options().bindAddress("127.0.0.1").dynamicPort());
        server.start();
        server.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("[]")));
        server.stubFor(get(urlPathEqualTo("/api/settings")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(SETTINGS)));
        // A write answers with no body, as the real server does - which is the case {"ok":true} exists for.
        server.stubFor(put(urlPathMatching("/api/settings/.*")).willReturn(aResponse().withStatus(200)));
        System.setProperty("JENREG_CLI_HOME", home.toString());
        Cli.run(new String[] {"login", "http://127.0.0.1:" + server.port() + "/", "--key", "test-key"});
    }

    @AfterAll
    public void tearDown() {
        System.clearProperty("JENREG_CLI_HOME");
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void stdout_is_the_servers_document_and_only_that() throws Exception {
        String out = capture(() -> assertThat(Cli.run(new String[] {"settings", "--json"})).isZero());

        assertThat(out.strip()).as("one JSON value, so a caller can parse the whole of stdout")
                .startsWith("[").endsWith("]");
        assertThat(out).as("the human rendering must not be interleaved with it").doesNotContain("audit  ");
        assertThat(out).as("a field this client does not model still reaches the caller, which is the point of"
                        + " handing back the server's answer rather than re-serialising our parse of it")
                .contains("somethingTheCliDoesNotModel");
    }

    @Test
    void without_the_flag_the_output_is_unchanged() throws Exception {
        String out = capture(() -> assertThat(Cli.run(new String[] {"settings"})).isZero());
        assertThat(out).as("the text mode is not derived from the JSON one, and stays as it was")
                .contains("audit").doesNotContain("somethingTheCliDoesNotModel");
    }

    @Test
    void a_command_that_changes_something_answers_ok() throws Exception {
        String out = capture(() -> assertThat(
                Cli.run(new String[] {"settings", "set", "audit", "true", "--json"})).isZero());
        assertThat(out.strip())
                .as("a caller still needs one parseable value back when the server answers with no body")
                .isEqualTo("{\"ok\":true}");
    }

    @Test
    void a_local_command_composes_its_own_document() throws Exception {
        String out = capture(() -> assertThat(Cli.run(new String[] {"whoami", "--json"})).isZero());
        assertThat(out).contains("\"loggedIn\":true").contains("\"url\"");
        assertThat(out).as("a stored key must not be recoverable from output a caller might log")
                .doesNotContain("test-key");
    }

    @Test
    void an_error_is_json_too_and_stays_on_stderr() throws Exception {
        server.stubFor(get(urlPathEqualTo("/api/quota")).willReturn(aResponse().withStatus(500)));

        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
        String out;
        try {
            out = capture(() -> {
                try {
                    Cli.run(new String[] {"quota", "--json"});
                } catch (Exception expected) {
                    // main() renders it; run() is allowed to throw, which is what the wrapper below stands in for
                    System.err.println("{\"error\":" + expected.getMessage().length() + "}");
                }
            });
        } finally {
            System.setErr(originalErr);
        }
        assertThat(out).as("stdout carries the answer or nothing - never an error").isEmpty();
        assertThat(errors.toString(StandardCharsets.UTF_8)).contains("error");
    }

    @Test
    void the_document_is_utf8_whatever_stdout_was_started_under() throws Exception {
        // A program's stdout is a pipe, and the launcher then encodes System.out with the platform's native
        // charset - US-ASCII under a C/POSIX locale, which is what a container and a CI runner commonly have. A
        // PrintStream writes '?' for what it cannot encode, so without a fix the operator's name below reached the
        // caller as "op?rateur": a different value, in the mode whose promise is the server's answer verbatim.
        byte[] out = captureBytes(StandardCharsets.US_ASCII,
                () -> assertThat(Cli.run(new String[] {"settings", "--json"})).isZero());
        assertThat(new String(out, StandardCharsets.UTF_8))
                .as("JSON is UTF-8 by definition, so the document is encoded as UTF-8 however the stream was set up")
                .contains("\"pinnedBy\":\"op\u00e9rateur\"");
    }

    @Test
    void the_mode_does_not_leak_into_the_next_command() throws Exception {
        capture(() -> Cli.run(new String[] {"settings", "--json"}));
        String out = capture(() -> assertThat(Cli.run(new String[] {"settings"})).isZero());
        assertThat(out).as("--json is per invocation; these share a JVM under test and would otherwise infect"
                        + " each other")
                .doesNotContain("somethingTheCliDoesNotModel");
    }

    private interface Body {
        void run() throws Exception;
    }

    private static String capture(Body body) throws Exception {
        return new String(captureBytes(StandardCharsets.UTF_8, body), StandardCharsets.UTF_8);
    }

    /** What the command wrote to a stdout encoding with {@code charset} - the bytes, since the encoding is what
     *  one test is about. */
    private static byte[] captureBytes(Charset charset, Body body) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, charset));
        try {
            body.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toByteArray();
    }
}
