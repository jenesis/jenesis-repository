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
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code --refresh} watches work that outlives the request, and stops when it finishes.
 *
 * <p>The rule it implements is the one every operator surface here follows: nothing blocks and nothing times out.
 * A command that starts long work returns as soon as the work is accepted, because holding the connection open
 * fails on exactly the deployment where the work takes long enough to be worth watching. Watching is then a thing
 * the caller asks for.
 *
 * <p>What is worth pinning is not that a loop runs - it is where the loop <em>stops</em>, and what a program reads
 * when it does. A watch that never ended would be the blocking command back again under a friendlier name, and a
 * watch that printed a document per poll would break the promise that {@code --json} answers exactly one JSON
 * value. Both are asserted here against a server whose answer changes under the poll.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CliRefreshTest {

    private static final String RUNNING =
            "{\"state\":\"running\",\"imported\":3,\"skipped\":0,\"droppedTotal\":0,\"dropped\":{}}";
    private static final String DONE =
            "{\"state\":\"done\",\"imported\":9,\"skipped\":1,\"droppedTotal\":0,\"dropped\":{}}";

    @TempDir
    private static Path home;

    private static WireMockServer server;

    @BeforeAll
    public void setUp() throws Exception {
        server = new WireMockServer(WireMockConfiguration.options().bindAddress("127.0.0.1").dynamicPort());
        server.start();
        server.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("[]")));
        // The job is running the first two times it is asked and finished the third, so a watch that stops only
        // because the first answer said so would pass nothing here.
        String path = "/api/repository/import/job-1";
        server.stubFor(get(urlPathEqualTo(path)).inScenario("import")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willSetStateTo("second")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody(RUNNING)));
        server.stubFor(get(urlPathEqualTo(path)).inScenario("import")
                .whenScenarioStateIs("second").willSetStateTo("finished")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody(RUNNING)));
        server.stubFor(get(urlPathEqualTo(path)).inScenario("import")
                .whenScenarioStateIs("finished")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody(DONE)));
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

    /** The stubs answer differently on each call, so a test that inherited another's position in the sequence
     *  would watch a job that had already finished - and pass for the wrong reason, which is what happened. */
    @BeforeEach
    public void freshJob() {
        server.resetScenarios();
    }

    @Test
    void a_watch_reprints_until_the_work_finishes_and_then_stops() throws Exception {
        String out = capture(() -> assertThat(Cli.run(new String[] {
                "import", "status", "releases", "job-1", "--refresh=1s"})).isZero());

        assertThat(out.lines().filter(line -> line.startsWith("state:")).count())
                .as("one rendering per poll, and the poll stopped when the job did rather than running on")
                .isEqualTo(3);
        assertThat(out).as("the last reading is the finished one").contains("state:    done");
        assertThat(out).as("and the progress before it was shown, which is the whole point of watching")
                .contains("state:    running");
    }

    @Test
    void a_watch_under_json_still_answers_exactly_one_value() throws Exception {
        String out = capture(() -> assertThat(Cli.run(new String[] {
                "import", "status", "releases", "job-1", "--refresh=1s", "--json"})).isZero());

        assertThat(out.strip()).as("one JSON value, so a caller can parse the whole of stdout")
                .startsWith("{").endsWith("}");
        assertThat(out).as("the final state, not the ones on the way to it - a script wants the outcome")
                .contains("\"state\":\"done\"");
        assertThat(out).as("an intermediate poll must not survive into the answer")
                .doesNotContain("\"state\":\"running\"");
    }

    @Test
    void asking_to_watch_something_that_cannot_be_watched_says_so() throws Exception {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
        try {
            capture(() -> assertThat(Cli.run(new String[] {"settings", "--refresh"})).isZero());
        } finally {
            System.setErr(originalErr);
        }
        assertThat(errors.toString(StandardCharsets.UTF_8))
                .as("the flag is global, so it is accepted everywhere and means something only where work "
                        + "outlives the request; a caller who asked to watch a point read hears that rather than "
                        + "assuming a loop is running")
                .contains("nothing to watch");
    }

    @Test
    void a_malformed_interval_says_what_it_takes() {
        assertThatThrownBy(() -> Cli.run(new String[] {"settings", "--refresh=soon"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("30s");
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
