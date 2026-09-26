package build.jenesis.repository.cli.test;

import module java.base;
import module org.junit.jupiter.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import build.jenesis.repository.cli.Cli;
import build.jenesis.repository.cli.Session;

import static org.assertj.core.api.Assertions.assertThat;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;

/**
 * The CLI argument dispatcher ({@link Cli#run}): a deploy's compliance verdict maps to the exit code a CI script keys
 * on - published or quarantined succeed (0), a rejection or a read-only repository fail (1) - and an unknown command
 * reports usage with a non-zero code, so a gate rejection cannot slip past a pipeline as a success. The review-and-
 * pipeline verbs map their own status codes: a quarantine listing renders each hold's reasons, a staging
 * promote conflict (409) and a forwarding retry with nothing parked (404) exit non-zero, and a not-installed staging
 * (501) or published index (404) reports the absence without failing the command. The governance and maintenance verbs
 * map theirs the same way: capabilities renders the module list, a not-installed rate-limit (501) or
 * retro-plan (501) reports the absence without failing, a provenance key with signing off (404) and a missing import
 * job (404) exit non-zero, and a batch {@code --explode zip} with a gate-rejected member exits non-zero.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CliDispatcherTest {

    private static final String CAPABILITIES = "{\"version\":1,"
            + "\"formats\":[{\"name\":\"maven\",\"ecosystem\":\"maven\"}],\"importSources\":[],\"signals\":[],"
            + "\"modules\":[{\"module\":\"build.jenesis.repository.search\",\"installed\":true,"
            + "\"enableKey\":\"search\",\"enabled\":true,\"live\":false}],"
            + "\"features\":{\"advisories\":true,\"advisoriesEnabled\":true,\"staging\":true,\"retention\":true,"
            + "\"scan\":false,\"provenance\":false,\"provenanceEnabled\":false,\"audit\":true,\"upstream\":true,"
            + "\"tokenExchange\":false,\"rateLimit\":true,\"dependents\":true,\"search\":true}}";
    private static final String ASSETS_PAGE1 = "{\"repository\":\"releases\",\"assets\":[{"
            + "\"path\":\"/maven/org/acme/lib/1.0/lib-1.0.jar\",\"size\":13,\"sha256\":\"abc123\","
            + "\"format\":\"maven\",\"ecosystem\":\"Maven\",\"coordinate\":\"org.acme:lib\",\"version\":\"1.0\","
            + "\"prerelease\":false}],\"cursor\":\"bWF2ZW4\"}";
    private static final String ASSETS_PAGE2 = "{\"repository\":\"releases\",\"assets\":[{"
            + "\"path\":\"/npm/left-pad/-/left-pad-1.3.0.tgz\",\"size\":21,\"sha256\":\"def456\","
            + "\"format\":\"npm\",\"ecosystem\":\"npm\",\"coordinate\":\"left-pad\",\"version\":\"1.3.0\","
            + "\"prerelease\":false}],\"cursor\":null}";

    @TempDir
    private static Path home;
    @TempDir
    private static Path work;

    private WireMockServer server;
    private volatile int deployStatus = 201;
    private volatile int dependentsStatus = 200;
    private volatile String dependentsBody = "{}";
    private volatile String dependentsQuery;
    private volatile String sbomBody = "{\"bomFormat\":\"CycloneDX\"}";
    private volatile int findingsStatus = 200;
    private volatile String findingsBody = "{\"available\":true,\"findings\":[]}";
    private final List<String> findingsQueries = new CopyOnWriteArrayList<>();
    private volatile String vulnerabilitiesBody = "{\"scanned\":true,\"vulnerable\":[]}";
    private final List<String> vulnerabilityQueries = new CopyOnWriteArrayList<>();
    private volatile int stagingListStatus = 200;
    private volatile String stagingListBody = "{\"repositories\":[]}";
    private volatile int retryStatus = 200;
    private volatile int indexStatus = 200;
    private volatile String indexBody = "{\"generation\":0,\"watermark\":\"\",\"rebased\":\"\",\"chunks\":[]}";
    private volatile String quarantineBody = "{\"events\":[]}";
    private volatile int rateLimitStatus = 200;
    private volatile int retroStatus = 200;
    private volatile int purgeStatus = 200;
    private final List<String> purgeQueries = new CopyOnWriteArrayList<>();
    private volatile int provKeyStatus = 200;
    private volatile int importStatusStatus = 200;
    private volatile String explodeBody = "{\"explode\":\"zip\",\"entries\":["
            + "{\"path\":\"/maven/a\",\"status\":\"stored\"},"
            + "{\"path\":\"/maven/b\",\"status\":\"rejected\",\"reason\":\"gate\"}],\"capped\":false}";

    @BeforeAll
    public void setUp() throws Exception {
        // One WireMock server whose single catch-all stub defers to a dispatcher that mirrors the hand-rolled context
        // tree: it routes by request path (longest-prefix, as HttpServer did), reads the live volatile fields a test
        // sets before its call and records the query strings the assertions read back. h2c is disabled so a streamed
        // deploy PUT stays on HTTP/1.1 (a plaintext HTTP/2 upgrade resets a streamed request body against Jetty).
        server = new WireMockServer(WireMockConfiguration.options()
                .bindAddress("localhost").dynamicPort().http2PlainDisabled(true)
                .extensions(new Dispatcher()));
        server.start();
        server.stubFor(any(anyUrl()).willReturn(aResponse().withTransformers("cli-dispatch")));
        System.setProperty("JENREG_CLI_HOME", home.toString());
        new Session(URI.create("http://localhost:" + server.port()), "jenk_test").save(home);
    }

    @AfterAll
    public void tearDown() {
        server.stop();
        System.clearProperty("JENREG_CLI_HOME");
    }

    @Test
    public void a_published_deploy_exits_zero() throws Exception {
        deployStatus = 201;
        assertThat(deploy()).isZero();
    }

    @Test
    public void a_quarantined_deploy_exits_zero() throws Exception {
        deployStatus = 202;
        assertThat(deploy()).isZero();
    }

    @Test
    public void a_rejected_deploy_exits_non_zero() throws Exception {
        deployStatus = 422;
        assertThat(deploy()).isEqualTo(1);
    }

    @Test
    public void a_read_only_repository_exits_non_zero() throws Exception {
        deployStatus = 405;
        assertThat(deploy()).isEqualTo(1);
    }

    @Test
    public void an_unknown_command_is_a_usage_error() throws Exception {
        // 2 rather than 1, and stderr rather than stdout: a caller has to be able to tell "you typed something
        // that does not exist" from "the server refused", and a program parsing stdout must not be handed a
        // help page where it expected data.
        assertThat(Cli.run(new String[] {"frobnicate"})).isEqualTo(2);
    }

    @Test
    public void purge_is_dry_run_first_and_deletes_only_with_the_flag() throws Exception {
        String report = capture(() -> assertThat(Cli.run(new String[] {"purge"})).isZero());
        assertThat(report).as("a bare purge prints the orphan report and touches nothing")
                .contains("build.x.phantom").contains("Nothing is removed automatically");

        purgeQueries.clear();
        String plan = capture(() -> assertThat(Cli.run(new String[] {"purge", "build.x.phantom"})).isZero());
        assertThat(plan).contains("would delete:").contains("Re-run with --delete");
        assertThat(purgeQueries).as("without the flag only the dry run is requested").hasSize(1);
        assertThat(purgeQueries.getFirst()).contains("dryRun=true");

        purgeQueries.clear();
        String delete = capture(() ->
                assertThat(Cli.run(new String[] {"purge", "build.x.phantom", "--delete"})).isZero());
        assertThat(delete).as("the delete prints the blast radius before purging")
                .contains("deleting:").contains("default/releases/p").contains("purged");
        assertThat(purgeQueries).as("the dry run always precedes the purge").hasSize(2);
        assertThat(purgeQueries.getFirst()).contains("dryRun=true");
        assertThat(purgeQueries.getLast()).contains("dryRun=false");

        purgeStatus = 404;
        try {
            String unknown = capture(() -> assertThat(Cli.run(new String[] {"purge", "no.such"})).isEqualTo(1));
            assertThat(unknown).contains("No storage namespace");
        } finally {
            purgeStatus = 200;
        }
    }

    @Test
    public void assets_prints_one_page_and_its_resume_cursor() throws Exception {
        String out = capture(() -> assertThat(Cli.run(new String[] {"assets", "releases"})).isZero());
        assertThat(out).contains("/maven/org/acme/lib/1.0/lib-1.0.jar").contains("org.acme:lib:1.0");
        assertThat(out).as("a full page prints the cursor to resume from").contains("next cursor: bWF2ZW4");
        assertThat(out).as("without --all only one page is fetched").doesNotContain("left-pad");
    }

    @Test
    public void assets_all_follows_the_cursor_to_the_end() throws Exception {
        String out = capture(() -> assertThat(Cli.run(new String[] {"assets", "releases", "--all"})).isZero());
        assertThat(out).contains("/maven/org/acme/lib/1.0/lib-1.0.jar")
                .contains("/npm/left-pad/-/left-pad-1.3.0.tgz");
        assertThat(out).as("the exhausted walk prints no resume cursor").doesNotContain("next cursor");
    }

    @Test
    public void dependents_prints_who_depends_on_a_coordinate() throws Exception {
        dependentsStatus = 200;
        dependentsBody = "{\"coordinate\":\"pkg:maven/x/core@1\","
                + "\"dependents\":[\"pkg:maven/x/app@1\",\"pkg:maven/x/lib@1\"],\"coordinates\":null}";
        String out = capture(() -> assertThat(
                Cli.run(new String[] {"dependents", "releases", "pkg:maven/x/core@1"})).isZero());
        assertThat(out).contains("pkg:maven/x/app@1").contains("pkg:maven/x/lib@1");
    }

    @Test
    public void dependents_prints_the_versions_declaring_a_package_apart_with_their_requirement() throws Exception {
        dependentsStatus = 200;
        dependentsBody = "{\"dependency\":\"lodash\",\"declared\":[{\"ecosystem\":\"npm\",\"coordinate\":\"app\","
                + "\"version\":\"1.0.0\",\"requirement\":\"^4.17.0\"},{\"ecosystem\":\"npm\",\"coordinate\":\"lib\","
                + "\"version\":\"2.0.0\",\"requirement\":\"\"}],\"nextDeclaredCursor\":\"tok\"}";
        String out = capture(() -> assertThat(
                Cli.run(new String[] {"dependents", "releases", "--package", "lodash", "--cursor", "c1"})).isZero());
        assertThat(dependentsQuery).as("the package and the cursor reach the declared answer")
                .contains("package=lodash").contains("after=c1");
        assertThat(out).contains("npm  app  1.0.0  ^4.17.0")
                .as("a declaration stating no requirement says so rather than printing nothing")
                .contains("npm  lib  2.0.0  -")
                .contains("next cursor: tok");
    }

    @Test
    public void dependents_reports_when_the_index_is_not_installed() throws Exception {
        dependentsStatus = 501;
        String out = capture(() -> assertThat(
                Cli.run(new String[] {"dependents", "releases", "pkg:maven/x/core@1"})).isZero());
        assertThat(out).contains("not installed");
    }

    @Test
    public void sbom_prints_the_generated_bom() throws Exception {
        sbomBody = "{\n  \"bomFormat\" : \"CycloneDX\",\n  \"specVersion\" : \"1.6\"\n}";
        String out = capture(() -> assertThat(Cli.run(
                new String[] {"sbom", "releases", "com/x/app/1/app-1.jar", "--format", "cyclonedx"})).isZero());
        assertThat(out).contains("CycloneDX").contains("1.6");
    }

    @Test
    public void findings_prints_the_ledger_grouped_by_coordinate_with_attribution_and_labels() throws Exception {
        findingsStatus = 200;
        findingsBody = "{\"available\":true,\"findings\":[{\"ecosystem\":\"Maven\","
                + "\"coordinate\":\"org.acme:lib\",\"version\":\"1.0\",\"id\":\"GHSA-x\","
                + "\"source\":\"osv\",\"kind\":\"vulnerability\",\"category\":\"advisory\","
                + "\"severity\":\"CRITICAL\",\"confidence\":1.0,\"description\":\"remote code execution\","
                + "\"references\":[\"CVE-1\"],\"provenance\":\"scan-sweep\","
                + "\"attributes\":{\"fixed\":\"2.0\"},\"firstSeen\":\"2026-07-01T00:00:00Z\","
                + "\"lastSeen\":\"2026-07-05T00:00:00Z\",\"supersededBy\":\"GHSA-better\","
                + "\"labels\":[{\"source\":\"ai\",\"name\":\"applicability\",\"value\":\"applies\","
                + "\"confidence\":0.7,\"when\":\"2026-07-05T00:00:00Z\"}]}]}";
        String out = capture(() -> assertThat(Cli.run(new String[] {"findings", "releases",
                "--kind", "vulnerability", "--source", "osv", "--category", "advisory",
                "--severity", "CRITICAL", "--coordinate", "org.acme:lib"})).isZero());
        assertThat(out).contains("org.acme:lib:1.0")
                .contains("[vulnerability] GHSA-x (CRITICAL, osv, advisory)")
                .contains("[superseded by GHSA-better]")
                .contains("remote code execution").contains("(fixed in 2.0)")
                .contains("label ai/applicability: applies");
        assertThat(findingsQueries.getLast()).as("every filter reaches the API as a query parameter")
                .contains("repo=releases").contains("kind=vulnerability").contains("source=osv")
                .contains("category=advisory").contains("severity=CRITICAL")
                .contains("coordinate=org.acme:lib");
    }

    @Test
    public void vulnerabilities_renders_the_ai_badges_and_sends_the_facets() throws Exception {
        vulnerabilitiesBody = "{\"scanned\":true,\"vulnerable\":[{"
                + "\"coordinate\":\"org.acme:lib:1.0\",\"advisories\":[{"
                + "\"id\":\"GHSA-x\",\"severity\":\"HIGH\",\"malicious\":false,\"fixed\":\"2.0\","
                + "\"reachability\":\"unknown\",\"applicability\":\"not-applicable\",\"signals\":[]}]}]}";
        String out = capture(() -> assertThat(Cli.run(new String[] {"vulnerabilities", "releases",
                "--reachability", "unknown", "--applicability", "not-applicable"})).isZero());
        assertThat(out).as("both AI labels render beside the advisory, separately attributed")
                .contains("GHSA-x").contains("unknown").contains("AI: not applicable");
        assertThat(vulnerabilityQueries.getLast()).as("both facets reach the API as query parameters")
                .contains("repo=releases").contains("reachability=unknown")
                .contains("applicability=not-applicable");
    }

    @Test
    public void findings_reports_an_empty_match_and_a_missing_module_distinctly() throws Exception {
        findingsStatus = 200;
        findingsBody = "{\"available\":true,\"findings\":[]}";
        String empty = capture(() -> assertThat(Cli.run(new String[] {"findings", "releases"})).isZero());
        assertThat(empty).contains("No recorded findings match.");
        findingsStatus = 501;
        String absent = capture(() -> assertThat(Cli.run(new String[] {"findings", "releases"})).isZero());
        assertThat(absent).contains("not installed");
    }

    @Test
    public void quarantine_lists_held_events_with_their_reasons() throws Exception {
        quarantineBody = "{\"events\":[{\"when\":\"2026-01-01T00:00:00Z\",\"path\":\"/maven/x/y/1/y-1.jar\","
                + "\"coordinate\":\"x:y\",\"verdict\":\"REJECT\",\"reasons\":[\"malware\"]}]}";
        String out = capture(() -> assertThat(Cli.run(new String[] {"quarantine", "releases"})).isZero());
        assertThat(out).contains("/maven/x/y/1/y-1.jar").contains("REJECT").contains("malware");
    }

    @Test
    public void staging_reports_when_it_is_not_installed() throws Exception {
        stagingListStatus = 501;
        String out = capture(() -> assertThat(Cli.run(new String[] {"staging", "releases"})).isZero());
        assertThat(out).contains("not installed");
    }

    @Test
    public void a_staging_promote_conflict_exits_non_zero() throws Exception {
        deployStatus = 409;
        String out = capture(() -> assertThat(
                Cli.run(new String[] {"staging", "promote", "releases", "stg-1"})).isEqualTo(1));
        assertThat(out).contains("already sealed");
    }

    @Test
    public void a_forwarding_retry_with_nothing_parked_exits_non_zero() throws Exception {
        retryStatus = 404;
        String out = capture(() -> assertThat(
                Cli.run(new String[] {"forwarding", "retry", "mirror", "/x"})).isEqualTo(1));
        assertThat(out).contains("Nothing parked");
    }

    @Test
    public void index_reports_when_the_module_is_not_installed() throws Exception {
        indexStatus = 404;
        String out = capture(() -> assertThat(Cli.run(new String[] {"index", "releases"})).isZero());
        assertThat(out).contains("not installed");
    }

    @Test
    public void capabilities_renders_the_module_list() throws Exception {
        String out = capture(() -> assertThat(Cli.run(new String[] {"capabilities"})).isZero());
        assertThat(out).contains("build.jenesis.repository.search").contains("search:");
    }

    @Test
    public void rate_limit_reports_when_not_installed() throws Exception {
        rateLimitStatus = 501;
        String out = capture(() -> assertThat(Cli.run(new String[] {"rate-limit"})).isZero());
        assertThat(out).contains("not installed");
    }

    @Test
    public void retro_plan_reports_when_not_installed() throws Exception {
        retroStatus = 501;
        String out = capture(() -> assertThat(Cli.run(new String[] {"retro-plan", "releases"})).isZero());
        assertThat(out).contains("not installed");
    }

    @Test
    public void provenance_key_reports_when_signing_is_off() throws Exception {
        provKeyStatus = 404;
        String out = capture(() -> assertThat(Cli.run(new String[] {"provenance", "key"})).isEqualTo(1));
        assertThat(out).contains("not enabled");
    }

    @Test
    public void import_status_reports_a_missing_job() throws Exception {
        importStatusStatus = 404;
        String out = capture(() -> assertThat(
                Cli.run(new String[] {"import", "status", "releases", "gone"})).isEqualTo(1));
        assertThat(out).contains("No import job");
    }

    @Test
    public void a_batch_explode_with_a_rejected_member_exits_non_zero() throws Exception {
        Path file = Files.writeString(work.resolve("bundle.zip"), "zip-bytes");
        String out = capture(() -> assertThat(Cli.run(new String[] {
                "deploy", "releases", "/maven/org/acme/", file.toString(), "--explode", "zip"})).isEqualTo(1));
        assertThat(out).contains("stored").contains("rejected").contains("gate");
    }

    /** Mirrors the hand-rolled context tree in one place: routes each request by path exactly as the HttpServer's
     *  longest-prefix dispatch did, reads the live volatile status/body fields a test set, and records the query
     *  strings the assertions read back. WireMock drains and records the request body itself, so the old handlers'
     *  {@code readAllBytes()} is implicit. */
    private final class Dispatcher implements ResponseDefinitionTransformerV2 {
        @Override
        public String getName() {
            return "cli-dispatch";
        }

        @Override
        public boolean applyGlobally() {
            return false;
        }

        @Override
        public ResponseDefinition transform(ServeEvent event) {
            String url = event.getRequest().getUrl();
            int mark = url.indexOf('?');
            String path = mark < 0 ? url : url.substring(0, mark);
            String query = mark < 0 ? null : URLDecoder.decode(url.substring(mark + 1), StandardCharsets.UTF_8);

            if (matches(path, "/api/capabilities")) {
                return respond(200, CAPABILITIES);
            }
            if (matches(path, "/api/rate-limit")) {
                return respond(rateLimitStatus, "{\"permitsPerMinute\":0}");
            }
            if (matches(path, "/api/licenses/retro/plan")) {
                return respond(retroStatus, "{\"mode\":\"denied\",\"count\":0,\"held\":[]}");
            }
            if (matches(path, "/api/provenance/key")) {
                return respond(provKeyStatus, "-----BEGIN PUBLIC KEY-----\n-----END PUBLIC KEY-----\n");
            }
            if (matches(path, "/api/repository/import")) {
                return respond(importStatusStatus, "{\"state\":\"done\",\"imported\":0,\"skipped\":0,"
                        + "\"skippedFormats\":[],\"cursor\":null,\"asset\":null,\"error\":\"\"}");
            }
            if (matches(path, "/api/dependents")) {
                dependentsQuery = query;
                return respond(dependentsStatus, dependentsBody);
            }
            if (matches(path, "/api/sbom")) {
                return respond(200, sbomBody);
            }
            if (matches(path, "/api/findings")) {
                findingsQueries.add(query);
                return respond(findingsStatus, findingsBody);
            }
            if (matches(path, "/api/vulnerabilities")) {
                vulnerabilityQueries.add(query);
                return respond(200, vulnerabilitiesBody);
            }
            if (matches(path, "/api/quarantine")) {
                return respond(200, quarantineBody);
            }
            // The list alone: a promote or drop sits beneath it and answers through the catch-all's deploy status.
            if (path.equals("/api/repository/staging")) {
                return respond(stagingListStatus, stagingListBody);
            }
            // A more specific route than the "/" catch-all, so a retry's status is controllable on its own; it answers
            // a bare status line even on 200, as the hand-rolled handler did.
            if (matches(path, "/api/forwarding/retry")) {
                return status(retryStatus);
            }
            if (matches(path, "/api/index")) {
                return respond(indexStatus, indexBody);
            }
            if (matches(path, "/api/assets")) {
                return respond(200, query != null && query.contains("cursor=") ? ASSETS_PAGE2 : ASSETS_PAGE1);
            }
            if (matches(path, "/api/admin/orphans")) {
                return respond(200, "{\"orphans\":[{\"namespace\":\"build.x.phantom\",\"objects\":2,\"bytes\":9}]}");
            }
            if (matches(path, "/api/admin/purge")) {
                purgeQueries.add(query);
                if (purgeStatus != 200) {
                    return status(purgeStatus);
                }
                return respond(200, "{\"namespace\":\"build.x.phantom\",\"dryRun\":"
                        + query.contains("dryRun=true")
                        + ",\"spaces\":[{\"prefix\":\"default/releases/p\",\"objects\":2,\"bytes\":9}],"
                        + "\"objects\":2,\"bytes\":9}");
            }
            // The "/" catch-all: an explode batch renders its report, an ordinary deploy answers the verdict status.
            if (event.getRequest().getHeader("Jenesis-Explode") != null) {
                return respond(200, explodeBody);
            }
            return status(deployStatus);
        }
    }

    /** The longest-prefix match the HttpServer used: a context matches its own path or any path beneath it. */
    private static boolean matches(String path, String context) {
        return path.equals(context) || path.startsWith(context + "/");
    }

    /** Mirrors the hand-rolled {@code respond}: a 200 with a body writes it; any other status is a bare status line. */
    private static ResponseDefinition respond(int status, String body) {
        if (status == 200 && body != null) {
            return aResponse().withStatus(200).withBody(body.getBytes(StandardCharsets.UTF_8)).build();
        }
        return status(status);
    }

    private static ResponseDefinition status(int status) {
        return aResponse().withStatus(status).build();
    }

    private interface Action {
        void run() throws Exception;
    }

    private static String capture(Action action) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private int deploy() throws Exception {
        Path file = Files.writeString(work.resolve("lib.jar"), "bytes");
        return Cli.run(new String[] {"deploy", "releases", "/maven/org/x/lib/1.0/lib-1.0.jar", file.toString()});
    }
}
