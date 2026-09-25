package build.jenesis.repository.cli.test;

import module java.base;
import module org.junit.jupiter.api;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import build.jenesis.repository.cli.Cli;
import build.jenesis.repository.cli.Commands;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every command reaches the server, at the endpoint it is supposed to reach.
 *
 * <p><b>What this catches that nothing else does.</b> The CLI is three layers - a registry entry, a handler that
 * parses the argument line, and a client method that builds a path - and each is individually plausible while the
 * chain is broken. A verb can be declared and dispatch to a handler whose arguments never line up, or call a client
 * method that builds a path the server does not serve. None of that fails to compile, and a unit test of the client
 * alone never runs the dispatcher, so the wiring in between was the one part with no cover at all. Here the whole
 * chain runs: a real argument line goes into {@link Cli#run}, and what comes out the other end is an HTTP request
 * recorded by a stand-in server.
 *
 * <p><b>Why the response does not matter.</b> The stub answers everything the same way, and a command is free to
 * fail afterwards on a body it cannot parse. What is asserted is that the request was made and where it went;
 * parsing is {@code RepositoryClientTest}'s subject, and conflating the two would mean maintaining a plausible
 * response body for every endpoint in the product just to prove a verb is plumbed in.
 *
 * <p><b>Why the census assertion is the important line.</b> {@link #every_command_is_covered_by_this_test} fails
 * when a command exists with no case here, so this cannot quietly fall behind the registry the way a hand-kept list
 * does. A new command is not done until it is bound and shown to be bound.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CliBindingTest {

    /**
     * One command line per noun, with the path prefix its request must carry.
     *
     * <p>The arguments are the shortest form that reaches the server: this asks whether a verb is wired, not
     * whether every flag it accepts is.
     */
    private record Binding(List<String> args, String path) {

        static Binding of(String path, String... args) {
            return new Binding(List.of(args), path);
        }
    }

    /** The nouns that never call the server: they read or write the stored session on disk. */
    private static final Map<String, List<String>> LOCAL = Map.of(
            "login", List.of("login", "http://127.0.0.1:1/", "--key", "k"),
            "logout", List.of("logout"),
            "whoami", List.of("whoami"));

    private static final Map<String, Binding> BOUND = Map.ofEntries(
            Map.entry("browse", Binding.of("/api/browse", "browse", "releases")),
            Map.entry("search", Binding.of("/api/search", "search", "releases", "acme")),
            Map.entry("assets", Binding.of("/api/assets", "assets", "releases")),
            Map.entry("dependents", Binding.of("/api/dependents", "dependents", "releases")),
            Map.entry("sbom", Binding.of("/api/sbom", "sbom", "releases")),
            Map.entry("origin", Binding.of("/api/origin", "origin", "releases")),
            Map.entry("attribution", Binding.of("/api/attribution", "attribution", "releases")),
            Map.entry("vulnerabilities", Binding.of("/api/vulnerabilities", "vulnerabilities", "releases")),
            Map.entry("findings", Binding.of("/api/findings", "findings", "releases")),
            Map.entry("licenses", Binding.of("/api/licenses", "licenses", "releases")),
            Map.entry("retro-plan", Binding.of("/api/licenses/retro/plan", "retro-plan", "releases")),
            Map.entry("quarantine", Binding.of("/api/quarantine", "quarantine", "releases")),
            Map.entry("signers", Binding.of("/api/signers", "signers", "releases")),
            Map.entry("signature", Binding.of("/api/signature", "signature", "releases", "/a/b.jar")),
            Map.entry("policy", Binding.of("/api/policy", "policy")),
            Map.entry("provenance", Binding.of("/api/provenance", "provenance", "releases", "/a/b.jar")),
            Map.entry("vex", Binding.of("/api/vex", "vex")),
            Map.entry("scans", Binding.of("/api/scans", "scans")),
            Map.entry("hardening", Binding.of("/api/hardening/verdict", "hardening", "releases")),
            Map.entry("staging", Binding.of("/api/repository/staging", "staging", "releases")),
            Map.entry("retention", Binding.of("/api/repository/retention", "retention", "releases")),
            Map.entry("cleanup", Binding.of("/api/repository/cleanup", "cleanup", "releases")),
            Map.entry("pins", Binding.of("/api/repository/pins", "pins", "releases")),
            Map.entry("lifecycle", Binding.of("/api/lifecycle", "lifecycle", "releases")),
            Map.entry("forwarding", Binding.of("/api/forwarding", "forwarding", "releases")),
            Map.entry("index", Binding.of("/api/index", "index", "releases")),
            Map.entry("forget-ecosystem",
                    Binding.of("/api/repository/forget-ecosystem", "forget-ecosystem", "releases", "npm")),
            Map.entry("purge", Binding.of("/api/admin/orphans", "purge")),
            Map.entry("deploy", Binding.of("/releases/", "deploy", "releases", "/a/b.jar", "@FILE")),
            Map.entry("import", Binding.of("/api/repository/import", "import", "releases",
                    "--source", "nexus", "--url", "http://x/", "--source-repo", "r")),
            Map.entry("cache", Binding.of("/api/cache/projects", "cache", "projects")),
            Map.entry("keylogin", Binding.of("/api/keylogin", "keylogin", "list")),
            Map.entry("scim", Binding.of("/api/scim/token", "scim", "token")),
            Map.entry("posture", Binding.of("/api/admin/posture", "posture")),
            Map.entry("caches", Binding.of("/api/admin/caches", "caches")),
            Map.entry("walks", Binding.of("/api/admin/walks", "walks")),
            Map.entry("consistency", Binding.of("/api/admin/consistency", "consistency")),
            Map.entry("logs", Binding.of("/api/admin/logs", "logs")),
            Map.entry("observability", Binding.of("/api/admin/observability", "observability")),
            Map.entry("spi", Binding.of("/api/admin/spi", "spi")),
            Map.entry("config", Binding.of("/api/config", "config")),
            Map.entry("webhook", Binding.of("/api/webhook", "webhook", "releases")),
            Map.entry("redirect-dns", Binding.of("/api/admin/redirect-dns/check", "redirect-dns", "check", "a:b")),
            Map.entry("tests", Binding.of("/api/tests/flaky", "tests", "flaky")),
            Map.entry("capabilities", Binding.of("/api/capabilities", "capabilities")),
            Map.entry("setup", Binding.of("/api/setup", "setup")),
            Map.entry("settings", Binding.of("/api/settings", "settings")),
            Map.entry("repos", Binding.of("/api/repositories", "repos")),
            Map.entry("upstreams", Binding.of("/api/upstreams", "upstreams")),
            Map.entry("quota", Binding.of("/api/quota", "quota")),
            Map.entry("rate-limit", Binding.of("/api/rate-limit", "rate-limit")),
            Map.entry("audit", Binding.of("/api/audit", "audit")),
            Map.entry("credentials", Binding.of("/api/credentials", "credentials")),
            Map.entry("groups", Binding.of("/api/groups", "groups")),
            Map.entry("principals", Binding.of("/api/principals", "principals")),
            Map.entry("roles", Binding.of("/api/roles", "roles")),
            Map.entry("trusts", Binding.of("/api/trusts", "trusts")));

    @TempDir
    private static Path home;

    private static WireMockServer server;

    @BeforeAll
    public void setUp() throws Exception {
        server = new WireMockServer(WireMockConfiguration.options().bindAddress("127.0.0.1").dynamicPort());
        server.start();
        // One answer for everything: this test is about the request, not the reply.
        server.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("[]")));
        System.setProperty("JENREG_CLI_HOME", home.toString());
        // Log in through the CLI rather than writing its session file here: the format is the CLI's business, and
        // a fixture that hand-rolls it silently stops logging in the day that changes - which is exactly what
        // happened when this was first written, and every case then reported the command as unwired.
        run("login", "http://127.0.0.1:" + server.port() + "/", "--key", "test-key");
    }

    private static void run(String... args) throws Exception {
        Cli.run(args);
    }

    @AfterAll
    public void tearDown() {
        System.clearProperty("JENREG_CLI_HOME");
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void every_command_is_covered_by_this_test() {
        Set<String> declared = new TreeSet<>(Commands.BY_NAME.keySet());
        Set<String> covered = new TreeSet<>(BOUND.keySet());
        covered.addAll(LOCAL.keySet());
        assertThat(covered)
                .as("every command must be exercised here - a command with no case is a command nobody has shown"
                        + " to be wired, and the three layers behind it all compile whether it is or not")
                .isEqualTo(declared);
    }

    /**
     * The write half of the lifecycle noun, which the table above does not reach: it exercises the read.
     *
     * <p>A mark names one <em>version</em> - that is what the stored flag carries and what the endpoint binds, from
     * the query rather than from a body. The client sent a JSON document of its own shape, with no version and a
     * field named {@code note}, so every {@code lifecycle mark} and every {@code lifecycle clear} the tool has ever
     * run was refused. Nothing caught it because a binding case asserts only the path, and both shapes reach it.
     */
    @Test
    void a_lifecycle_mark_and_a_clear_carry_the_version_and_state_the_endpoint_binds() throws Exception {
        server.resetRequests();
        run("lifecycle", "mark", "releases", "org.acme:lib", "1.0", "deprecated", "--message", "please upgrade");
        run("lifecycle", "clear", "releases", "org.acme:lib", "1.0");

        List<LoggedRequest> requests = server.findAll(RequestPatternBuilder.allRequests());
        assertThat(requests).hasSize(2);
        assertThat(requests.getFirst().getMethod().toString()).isEqualTo("POST");
        assertThat(requests.getFirst().getUrl())
                .as("a mark must carry every parameter the endpoint requires, in the query")
                .contains("/api/lifecycle")
                .contains("repository=releases")
                .contains("coordinate=org.acme%3Alib")
                .contains("version=1.0")
                .contains("state=deprecated")
                .contains("message=please");
        assertThat(requests.get(1).getMethod().toString()).isEqualTo("DELETE");
        assertThat(requests.get(1).getUrl())
                .as("and a clear names the version too, or it clears nothing")
                .contains("version=1.0");
    }

    @TestFactory
    Stream<DynamicTest> every_command_reaches_its_endpoint() {
        return BOUND.entrySet().stream().map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
            Binding binding = entry.getValue();
            server.resetRequests();
            List<String> args = new ArrayList<>(binding.args());
            for (int i = 0; i < args.size(); i++) {
                if (args.get(i).equals("@FILE")) {
                    Path file = home.resolve("payload.bin");
                    Files.writeString(file, "{}");
                    args.set(i, file.toString());
                }
            }
            try {
                Cli.run(args.toArray(String[]::new));
            } catch (Exception failedAfterTheCall) {
                // Expected for anything whose stub reply it cannot parse; the request is what is under test.
            }
            List<LoggedRequest> requests = server.findAll(RequestPatternBuilder.allRequests());
            assertThat(requests)
                    .as("'%s' made no request at all - it is declared and dispatched but reaches no endpoint",
                            String.join(" ", binding.args()))
                    .isNotEmpty();
            assertThat(requests).as("'%s' must reach %s", String.join(" ", binding.args()), binding.path())
                    .anySatisfy(request -> assertThat(request.getUrl()).contains(binding.path()));

            // --json is one mechanism for every command rather than a renderer per command, and this is what
            // says so: the same line, with the flag, must put exactly one JSON value on stdout and nothing else.
            List<String> asJson = new ArrayList<>(args);
            asJson.add("--json");
            String out = capture(() -> {
                try {
                    Cli.run(asJson.toArray(String[]::new));
                } catch (Exception failedAfterTheCall) {
                    // as above - the request is the subject, not the reply
                }
            });
            // Either a document or nothing: a command that fails on the stub's reply reports an error instead,
            // and an error belongs on stderr. What must never appear is the human rendering, which is what this
            // catches - the contract for the document itself is CliJsonOutputTest's subject, against a reply the
            // command can actually parse.
            if (!out.isBlank()) {
                assertThat(out.strip())
                        .as("'%s --json' put something other than one JSON value on stdout",
                                String.join(" ", binding.args()))
                        .matches("(?s)^[\\[{].*[\\]}]$");
            }
        }));
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
