package build.jenesis.repository.cli.test;

import module java.base;
import module java.net.http;
import module org.junit.jupiter.api;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import build.jenesis.repository.cli.RepositoryClient;
import build.jenesis.repository.cli.SettingsClient;
import build.jenesis.repository.cli.RiskClient;
import build.jenesis.repository.cli.ReviewClient;
import build.jenesis.repository.cli.ProvenanceClient;
import build.jenesis.repository.cli.LifecycleClient;
import build.jenesis.repository.cli.ContentsClient;
import build.jenesis.repository.cli.AccessClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the CLI's API client against a JDK HTTP server standing in for the repository: it parses the settings,
 * browse, search and credential responses into their fields (including a credential's nested grants, which the flat
 * matchers must step over), and sends authenticated set/clear, deploy, mint and revoke requests to the right keyed
 * paths - the same {@code /api/*} and Maven surfaces the console and API drive, exercised from the command line's
 * collaborator. The review-and-pipeline surfaces are proved the same way: the license facets, the quarantine
 * review (release/discard), the forwarding outbox and its retry, the staging list and promote/drop, the published-index
 * descriptor, and the per-tenant settings slice ({@code ?tenant=} threaded through the settings routes). The governance
 * and maintenance verbs round it out: the capabilities module list, quota/rate-limit/policy, roles/trusts, the
 * audit trail, cleanup/retention/pins, the retro-plan dry run, the credential sub-ops (grants/expiry/rotate/allowed-ips),
 * import(+status), the provenance material/key, and the batch {@code --explode zip} header and its per-entry manifest.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RepositoryClientTest {

    private static final String SETTINGS = "["
            + "{\"key\":\"license-allowed\",\"value\":\"apache,mit\",\"defaultValue\":\"\","
            + "\"overridden\":true,\"appliesImmediately\":true,\"pinned\":false,\"pinnedBy\":\"\"},"
            + "{\"key\":\"audit\",\"value\":\"true\",\"defaultValue\":\"true\","
            + "\"overridden\":false,\"appliesImmediately\":false,\"pinned\":false,\"pinnedBy\":\"\"},"
            + "{\"key\":\"proxy-enabled\",\"value\":\"false\",\"defaultValue\":\"true\","
            + "\"overridden\":false,\"appliesImmediately\":true,\"pinned\":true,"
            + "\"pinnedBy\":\"environment variable\"}]";
    private static final String SETTINGS_BUNDLE =
            "{\n  \"core\": {\n    \"immaturity-hold-days\": \"3\"\n  }\n}\n";
    private static final String BROWSE = "{\"prefix\":\"/maven\",\"entries\":[\"org\",\"com\"]}";
    private static final String SEARCH = "{\"mode\":\"NAME\",\"indexed\":false,"
            + "\"results\":[\"org.acme:lib:1.0\",\"org.acme:lib:2.0\"]}";
    private static final String CREDENTIALS = "[{\"id\":\"abc123\",\"label\":\"ci\",\"created\":\"\","
            + "\"expires\":\"2027-01-01T00:00:00Z\",\"lastUsed\":\"\",\"lastUsedAddress\":\"\",\"useCount\":3,"
            + "\"allowedAddresses\":\"\",\"grants\":{\"*/releases\":\"deploy\"}}]";
    private static final String MINTED = "{\"id\":\"def456\",\"key\":\"jenk_acme.secret\",\"expires\":\"\"}";
    private static final String REPOSITORIES =
            "[{\"name\":\"mirror\",\"value\":\"fallback https://repo1.maven.org/maven2/\"}]";
    private static final String UPSTREAMS = "[{\"name\":\"npm\",\"value\":\"https://npm.internal/\"}]";
    private static final String UPSTREAM_AUTH = "[\"nexus.internal\"]";
    private static final String PROVENANCE = "{\"payloadType\":\"application/vnd.in-toto+json\","
            + "\"payload\":\"eyJfdHlwZSI6Im4ifQ==\",\"signatures\":[{\"keyid\":\"abc\",\"sig\":\"c2ln\"}]}";
    private static final String VULNERABILITIES = "{\"scanned\":true,\"vulnerable\":[{"
            + "\"coordinate\":\"org.apache.logging.log4j:log4j-core:2.14.1\",\"advisories\":[{"
            + "\"id\":\"GHSA-jfh8-c2jp-5v3q\",\"severity\":\"CRITICAL\",\"malicious\":false,"
            + "\"fixed\":\"2.17.1\",\"reachability\":\"reachable\",\"applicability\":\"applies\","
            + "\"signals\":[{\"name\":\"known-exploited\",\"label\":\"Known exploited\","
            + "\"value\":\"known-exploited\",\"rank\":1.0},"
            + "{\"name\":\"epss\",\"label\":\"EPSS\",\"value\":\"EPSS 94%\",\"rank\":0.94373}]}]}]}";
    private static final String LICENSES = "{\"state\":\"done\",\"startedAt\":\"2026-01-01T00:00:00Z\","
            + "\"finishedAt\":\"2026-01-01T00:00:05Z\",\"failure\":null,\"versions\":15,"
            + "\"categories\":[{\"value\":\"permissive\",\"versions\":12},"
            + "{\"value\":\"strong-copyleft\",\"versions\":3}],"
            + "\"licenses\":[{\"value\":\"Apache-2.0\",\"versions\":8},{\"value\":\"MIT\",\"versions\":4}],"
            + "\"rows\":5,\"truncated\":false}";
    private static final String QUARANTINE = "{\"events\":[{\"when\":\"2026-01-01T00:00:00Z\","
            + "\"path\":\"/maven/org/acme/lib/1.0/lib-1.0.jar\",\"coordinate\":\"org.acme:lib\","
            + "\"verdict\":\"QUARANTINE\",\"reasons\":[\"unsigned\",\"license unknown\"]}]}";
    private static final String FORWARDING = "{\"entries\":[{\"path\":\"/npm/left-pad/-/left-pad-1.0.0.tgz\","
            + "\"ecosystem\":\"npm\",\"attempts\":3,\"parked\":true,\"status\":\"parked\",\"delivered\":1,"
            + "\"error\":\"502 from mirror\"}]}";
    private static final String STAGING = "{\"repositories\":[{\"id\":\"stg-abc\",\"state\":\"OPEN\",\"items\":4}]}";
    private static final String INDEX = "{\"generation\":7,\"watermark\":\"2026-01-01T00:00:00Z\",\"rebased\":\"\","
            + "\"chunks\":[{\"id\":\"aa\",\"uncompressedSize\":100,\"compressedSize\":40,\"records\":9,"
            + "\"minPublished\":\"2025-01-01T00:00:00Z\",\"maxPublished\":\"2026-01-01T00:00:00Z\"}]}";
    private static final String ASSETS_PAGE1 = "{\"repository\":\"releases\",\"assets\":[{"
            + "\"path\":\"/maven/org/acme/lib/1.0/lib-1.0.jar\",\"size\":13,\"sha256\":\"abc123\","
            + "\"format\":\"maven\",\"ecosystem\":\"Maven\",\"coordinate\":\"org.acme:lib\",\"version\":\"1.0\","
            + "\"prerelease\":false}],\"next\":\"bWF2ZW4\"}";
    private static final String ASSETS_PAGE2 = "{\"repository\":\"releases\",\"assets\":[{"
            + "\"path\":\"/npm/left-pad/-/left-pad-1.3.0.tgz\",\"size\":21,\"sha256\":\"def456\","
            + "\"format\":\"npm\",\"ecosystem\":\"npm\",\"coordinate\":\"left-pad\",\"version\":\"1.3.0\","
            + "\"prerelease\":false}],\"next\":null}";
    private static final String CAPABILITIES = "{\"version\":1,"
            + "\"formats\":[{\"name\":\"maven\",\"ecosystem\":\"maven\"}],"
            + "\"importSources\":[{\"name\":\"nexus\",\"label\":\"Nexus\",\"requiresFormat\":false}],"
            + "\"signals\":[],"
            + "\"modules\":[{\"module\":\"build.jenesis.repository.search\",\"installed\":true,"
            + "\"enableKey\":\"search\",\"enabled\":true,\"live\":false},"
            + "{\"module\":\"build.jenesis.repository.audit.store\",\"installed\":false,\"enableKey\":null,"
            + "\"enabled\":false,\"live\":false}],"
            + "\"features\":{\"advisories\":true,\"advisoriesEnabled\":true,\"staging\":true,\"retention\":true,"
            + "\"provenanceEnabled\":false,\"upstream\":true,\"tokenExchange\":false,\"rateLimit\":true},"
            // the module-contributed flags sit at the top level: each is written by the module that owns it,
            // not lifted into the server's own view by a second fan-out
            + "\"scan\":false,\"provenance\":false,\"audit\":true,\"dependents\":true,\"search\":true,"
            + "\"walk\":false,\"gc\":false}";
    private static final String QUOTA = "{\"maxBytes\":1073741824,\"usedBytes\":2048}";
    private static final String RATE_LIMIT = "{\"permitsPerMinute\":600}";
    private static final String POLICY = "{\"defaultLifetime\":\"P90D\",\"maxLifetime\":\"P365D\"}";
    private static final String ROLES = "{\"read-only\":\"repository:read\","
            + "\"deploy\":\"repository:read,repository:write\"}";
    private static final String TRUSTS = "[{\"name\":\"ci\",\"issuer\":\"https://token.actions.githubusercontent.com\","
            + "\"audience\":\"jenesis\",\"subject\":\"repo:acme/app\",\"scope\":\"releases\",\"rights\":\"deploy\","
            + "\"ttl\":\"PT1H\"}]";
    private static final String AUDIT = "[{\"at\":\"2026-01-01T00:00:00Z\",\"actor\":\"ci\","
            + "\"action\":\"credential.mint\",\"target\":\"abc\"}]";
    private static final String RETENTION =
            "{\"keepLast\":5,\"maxAge\":\"P30D\",\"prereleaseExpiry\":\"\",\"notDownloadedFor\":\"\"}";
    private static final String PINS = "{\"pinned\":[\"Maven:org.acme:lib:1.0\"]}";
    private static final String CLEANUP = "{\"plan\":false,\"state\":\"done\",\"evictedCount\":1,"
            + "\"evicted\":[\"org.acme:lib:0.9 - superseded\"],\"gc\":{\"installed\":true,\"complete\":true,"
            + "\"condemned\":3,\"spared\":0,\"collected\":3,\"refusal\":\"\"}}";
    private static final String ORPHANS = "{\"orphans\":[{\"namespace\":\"build.jenesis.repository.phantom\","
            + "\"objects\":3,\"bytes\":42}]}";
    private static final String PURGE = "{\"namespace\":\"build.jenesis.repository.phantom\",\"dryRun\":true,"
            + "\"spaces\":[{\"prefix\":\"default/releases/phantomspace\",\"objects\":3,\"bytes\":42}],"
            + "\"objects\":3,\"bytes\":42}";
    private static final String RETRO = "{\"mode\":\"denied\",\"state\":\"done\",\"count\":2,\"held\":[{\"ecosystem\":\"maven\","
            + "\"coordinate\":\"org.gnu:x\",\"version\":\"1.0\",\"reasons\":[\"GPL-3.0 denied\"]}]}";
    private static final String PROV_MATERIAL = "{\"envelope\":\"{\\\"payload\\\":\\\"e\\\"}\","
            + "\"certificateChain\":\"-----BEGIN CERTIFICATE-----\","
            + "\"transparencyLog\":{\"uuid\":\"abcd\",\"logId\":\"log\",\"logIndex\":42,\"integratedTime\":1700000000,"
            + "\"signedEntryTimestamp\":\"sig\",\"canonicalizedBody\":\"body\","
            + "\"inclusionProof\":{\"logIndex\":42,\"treeSize\":100,\"rootHash\":\"root\",\"hashes\":[\"h1\"],"
            + "\"checkpoint\":\"cp\"}}}";
    private static final String PROV_KEY = "-----BEGIN PUBLIC KEY-----\nMII\n-----END PUBLIC KEY-----\n";
    private static final String ROTATED =
            "{\"id\":\"newid789\",\"key\":\"jenk_acme.rotated\",\"expires\":\"2026-02-01T00:00:00Z\"}";
    private static final String IMPORT_JOB = "{\"job\":\"job-123\",\"state\":\"running\"}";
    private static final String IMPORT_STATUS = "{\"state\":\"running\",\"imported\":10,\"skipped\":2,"
            + "\"skippedFormats\":[\"cocoapods\"],\"cursor\":\"c\",\"asset\":\"org/acme/lib\",\"error\":\"\"}";
    private static final String EXPLODE_MANIFEST = "{\"explode\":\"zip\",\"entries\":["
            + "{\"path\":\"/maven/org/acme/a/1/a-1.pom\",\"status\":\"stored\"},"
            + "{\"path\":\"/maven/org/gnu/b/1/b-1.pom\",\"status\":\"rejected\",\"reason\":\"license denied\"}],"
            + "\"capped\":false}";

    private WireMockServer server;
    private RepositoryClient client;

    @TempDir
    Path work;

    private volatile String lastMethod;
    private volatile String lastPath;
    private volatile String lastQuery;
    private volatile String lastBody;
    private volatile String lastKey;
    private volatile String lastExplode;

    @BeforeAll
    public void start() throws IOException {
        server = new WireMockServer(WireMockConfiguration.options().bindAddress("localhost").dynamicPort());
        // Capture the last request's shape for the assertions, exactly as the hand-rolled handler recorded it.
        server.addMockServiceRequestListener((request, response) -> {
            lastMethod = request.getMethod().getName();
            String url = request.getUrl();
            int mark = url.indexOf('?');
            lastPath = mark < 0 ? url : url.substring(0, mark);
            // Decode to match the hand-rolled capture (getRequestURI().getQuery() returned the decoded query), since
            // WireMock's getUrl() keeps the raw percent-encoded form.
            lastQuery = mark < 0 ? null : URLDecoder.decode(url.substring(mark + 1), UTF_8);
            lastKey = request.getHeader("Jenesis-Repository-Key");
            lastExplode = request.getHeader("Jenesis-Explode");
            lastBody = request.getBodyAsString();
        });

        // The GET read surface: a fixed body per path, exactly as the hand-rolled switch answered.
        Map<String, String> gets = new LinkedHashMap<>();
        gets.put("/api/settings", SETTINGS);
        gets.put("/api/settings/export", SETTINGS_BUNDLE);
        gets.put("/api/browse", BROWSE);
        gets.put("/api/search", SEARCH);
        gets.put("/api/credentials", CREDENTIALS);
        gets.put("/api/repositories", REPOSITORIES);
        gets.put("/api/upstreams", UPSTREAMS);
        gets.put("/api/upstreams/auth", UPSTREAM_AUTH);
        gets.put("/api/vulnerabilities", VULNERABILITIES);
        gets.put("/api/provenance/key", PROV_KEY);
        gets.put("/api/licenses", LICENSES);
        gets.put("/api/quarantine", QUARANTINE);
        gets.put("/api/forwarding", FORWARDING);
        gets.put("/api/repository/staging", STAGING);
        gets.put("/api/index", INDEX);
        gets.put("/api/capabilities", CAPABILITIES);
        gets.put("/api/quota", QUOTA);
        gets.put("/api/rate-limit", RATE_LIMIT);
        gets.put("/api/policy", POLICY);
        gets.put("/api/roles", ROLES);
        gets.put("/api/trusts", TRUSTS);
        gets.put("/api/audit", AUDIT);
        gets.put("/api/licenses/retro/plan", RETRO);
        gets.put("/api/admin/orphans", ORPHANS);
        gets.put("/api/repository/retention", RETENTION);
        gets.put("/api/repository/pins", PINS);
        gets.put("/api/repository/cleanup/plan", CLEANUP);
        gets.put("/api/repository/import/job-123", IMPORT_STATUS);
        gets.forEach((path, payload) -> server.stubFor(get(urlPathEqualTo(path))
                .willReturn(aResponse().withStatus(200).withBody(payload))));
        // The two query-dependent GET reads: a token-bearing query outranks its plain sibling.
        server.stubFor(get(urlPathEqualTo("/api/provenance")).atPriority(1)
                .withQueryParam("material", matching(".*"))
                .willReturn(aResponse().withStatus(200).withBody(PROV_MATERIAL)));
        server.stubFor(get(urlPathEqualTo("/api/provenance")).atPriority(5)
                .willReturn(aResponse().withStatus(200).withBody(PROVENANCE)));
        server.stubFor(get(urlPathEqualTo("/api/assets")).atPriority(1)
                .withQueryParam("after", matching(".*"))
                .willReturn(aResponse().withStatus(200).withBody(ASSETS_PAGE2)));
        server.stubFor(get(urlPathEqualTo("/api/assets")).atPriority(5)
                .willReturn(aResponse().withStatus(200).withBody(ASSETS_PAGE1)));

        // The write surface, one stub per hand-rolled branch.
        server.stubFor(post(urlPathEqualTo("/api/credentials"))
                .willReturn(aResponse().withStatus(201).withBody(MINTED)));
        server.stubFor(post(urlPathMatching(".*/rotate"))
                .willReturn(aResponse().withStatus(201).withBody(ROTATED)));
        server.stubFor(post(urlPathEqualTo("/api/repository/cleanup"))
                .willReturn(aResponse().withStatus(200).withBody(CLEANUP)));
        server.stubFor(post(urlPathEqualTo("/api/admin/purge")).atPriority(1)
                .withQueryParam("namespace", equalTo("build.jenesis.repository.phantom"))
                .willReturn(aResponse().withStatus(200).withBody(PURGE)));
        server.stubFor(post(urlPathEqualTo("/api/admin/purge")).atPriority(5)
                .willReturn(aResponse().withStatus(404)));
        server.stubFor(post(urlPathEqualTo("/api/repository/import"))
                .willReturn(aResponse().withStatus(202).withBody(IMPORT_JOB)));
        server.stubFor(post(urlPathEqualTo("/api/quarantine/discard"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"discarded\":[\"/maven/org/acme/lib/1.0/lib-1.0.jar\"],\"absent\":[]}")));
        // A framework-rendered JSON error body: it starts with '{' but is not a batch manifest.
        server.stubFor(put(urlPathMatching(".*/errorbody/.*")).atPriority(1)
                .withHeader("Jenesis-Explode", matching(".*"))
                .willReturn(aResponse().withStatus(403)
                        .withBody("{\"timestamp\":\"2026-01-01T00:00:00Z\",\"status\":403,\"error\":\"Forbidden\"}")));
        server.stubFor(put(urlPathMatching(".*/maven/.*")).atPriority(2)
                .withHeader("Jenesis-Explode", matching(".*"))
                .willReturn(aResponse().withStatus(200).withBody(EXPLODE_MANIFEST)));
        server.stubFor(put(urlPathMatching(".*/maven/.*")).atPriority(5)
                .willReturn(aResponse().withStatus(201)));
        // A refusal and a hold as the deploy edge answers them, and a refusal in npm's error document.
        server.stubFor(put(urlPathMatching(".*/refused/.*")).atPriority(3)
                .willReturn(aResponse().withStatus(422).withBody(REFUSED)));
        server.stubFor(put(urlPathMatching(".*/held/.*")).atPriority(3)
                .willReturn(aResponse().withStatus(202).withBody(HELD)));
        server.stubFor(put(urlPathMatching(".*/npm-refused/.*")).atPriority(3)
                .willReturn(aResponse().withStatus(422).withBody("{\"error\":\"" + REFUSED + "\"}")));
        // Any other request answers 200 with no body, matching the hand-rolled default.
        server.stubFor(any(anyUrl()).atPriority(100).willReturn(aResponse().withStatus(200)));

        server.start();
        client = new RepositoryClient(URI.create("http://localhost:" + server.port()),
                "jenk_acme.secret", HttpClient.newHttpClient());
    }

    @AfterAll
    public void stop() {
        server.stop();
    }

    @Test
    void the_settings_list_is_parsed_into_its_fields() throws IOException, InterruptedException {
        List<SettingsClient.Setting> settings = client.settings().settings();
        assertThat(settings).hasSize(3);
        SettingsClient.Setting first = settings.get(0);
        assertThat(first.key()).isEqualTo("license-allowed");
        assertThat(first.value()).isEqualTo("apache,mit");
        assertThat(first.overridden()).isTrue();
        assertThat(first.appliesImmediately()).isTrue();
        assertThat(first.pinned()).as("an unpinned key").isFalse();
        assertThat(settings.get(1).appliesImmediately()).as("a restart-only setting").isFalse();
        SettingsClient.Setting pinned = settings.get(2);
        assertThat(pinned.key()).isEqualTo("proxy-enabled");
        assertThat(pinned.pinned()).as("a key fixed above the store is pinned").isTrue();
        assertThat(pinned.pinnedBy()).as("the CLI surfaces the pinning source").isEqualTo("environment variable");
    }

    @Test
    void set_sends_an_authenticated_put_to_the_keyed_path() throws IOException, InterruptedException {
        client.settings().setSetting("license-allowed", "apache,mit");
        assertThat(lastMethod).isEqualTo("PUT");
        assertThat(lastPath).isEqualTo("/api/settings/license-allowed");
        assertThat(lastKey).as("the stored key authenticates the request").isEqualTo("jenk_acme.secret");
        assertThat(lastBody).isEqualTo("{\"value\":\"apache,mit\"}");
    }

    @Test
    void settings_export_returns_the_bundle_and_import_posts_it_back() throws IOException, InterruptedException {
        String bundle = client.settings().exportSettings();
        assertThat(lastMethod).isEqualTo("GET");
        assertThat(lastPath).isEqualTo("/api/settings/export");
        assertThat(bundle).as("the raw bundle is returned unparsed, so it re-imports byte-identically")
                .isEqualTo(SETTINGS_BUNDLE);

        client.settings().importSettings(bundle);
        assertThat(lastMethod).isEqualTo("POST");
        assertThat(lastPath).isEqualTo("/api/settings/import");
        assertThat(lastKey).as("the stored key authenticates the request").isEqualTo("jenk_acme.secret");
        assertThat(lastBody).as("the bundle is posted back verbatim").isEqualTo(SETTINGS_BUNDLE);
    }

    @Test
    void clear_sends_a_delete_to_the_keyed_path() throws IOException, InterruptedException {
        client.settings().clearSetting("proxy");
        assertThat(lastMethod).isEqualTo("DELETE");
        assertThat(lastPath).isEqualTo("/api/settings/proxy");
    }

    @Test
    void browse_lists_the_entries_under_a_prefix() throws IOException, InterruptedException {
        assertThat(client.contents().browse("releases", "/maven")).containsExactly("org", "com");
        assertThat(lastPath).isEqualTo("/api/browse");
        assertThat(lastQuery).as("the decoded query the client sent").contains("repo=releases").contains("prefix=/maven");
    }

    @Test
    void search_lists_matching_coordinates_and_how_the_repository_answered() throws IOException, InterruptedException {
        ContentsClient.Found found = client.contents().search("releases", "lib");
        assertThat(found.results()).containsExactly("org.acme:lib:1.0", "org.acme:lib:2.0");
        assertThat(found.mode()).isEqualTo("NAME");
        assertThat(found.indexed()).isFalse();
        assertThat(lastQuery).contains("q=lib");
    }

    @Test
    void the_vulnerability_report_is_parsed_over_its_nested_advisories() throws IOException, InterruptedException {
        RiskClient.VulnerabilityReport report = client.risk().vulnerabilities("releases");
        assertThat(lastPath).isEqualTo("/api/vulnerabilities");
        assertThat(lastQuery).contains("repo=releases");
        assertThat(report.scanned()).isTrue();
        assertThat(report.vulnerable()).singleElement().satisfies(artifact -> {
            assertThat(artifact.coordinate()).isEqualTo("org.apache.logging.log4j:log4j-core:2.14.1");
            assertThat(artifact.advisories()).singleElement().satisfies(advisory -> {
                assertThat(advisory.id()).isEqualTo("GHSA-jfh8-c2jp-5v3q");
                assertThat(advisory.severity()).isEqualTo("CRITICAL");
                assertThat(advisory.knownExploited()).isTrue();
                assertThat(advisory.epss()).isEqualTo(0.94373);
                assertThat(advisory.fixed()).isEqualTo("2.17.1");
                assertThat(advisory.reachability()).isEqualTo("reachable");
                assertThat(advisory.applicability()).isEqualTo("applies");
            });
        });
    }

    @Test
    void the_vulnerability_facets_travel_as_query_parameters() throws IOException, InterruptedException {
        client.risk().vulnerabilities("releases", "unknown", "not-applicable");
        assertThat(lastPath).isEqualTo("/api/vulnerabilities");
        assertThat(lastQuery).contains("repo=releases")
                .contains("reachability=unknown").contains("applicability=not-applicable");
    }

    @Test
    void provenance_returns_the_signed_attestation_for_a_path() throws IOException, InterruptedException {
        String attestation = client.provenance().provenance("releases", "/maven/org/acme/lib/1.0/lib-1.0.pom");
        assertThat(lastPath).isEqualTo("/api/provenance");
        assertThat(lastQuery).contains("repo=releases").contains("path=/maven/org/acme/lib/1.0/lib-1.0.pom");
        assertThat(attestation).isEqualTo(PROVENANCE);
    }

    @Test
    void deploy_sends_the_bytes_and_returns_the_verdict_status() throws IOException, InterruptedException {
        int status = client.contents().deploy("releases", "/maven/org/acme/lib/1.0/lib-1.0.jar",
                "a library jar".getBytes(UTF_8)).status();
        assertThat(status).isEqualTo(201);
        assertThat(lastMethod).isEqualTo("PUT");
        assertThat(lastPath).isEqualTo("/repository/acme/releases/maven/org/acme/lib/1.0/lib-1.0.jar");
        assertThat(lastKey).isEqualTo("jenk_acme.secret");
        assertThat(lastBody).isEqualTo("a library jar");
    }

    private static final String REFUSED = "Refused by the compliance gate: Denied license AGPL-3.0. Nothing was "
            + "published; the refusal and its findings are listed on the repository's Refused screen.";

    private static final String HELD = "Held for review: No license declared. It is stored but not served until a "
            + "reviewer releases it on the repository's Quarantine screen.";

    @Test
    void a_held_or_refused_deploy_carries_what_the_server_said_in_whichever_shape_it_said_it()
            throws IOException, InterruptedException {
        assertThat(client.contents().deploy("releases", "/refused/a-1.0.jar", new byte[]{1}))
                .isEqualTo(new ContentsClient.Deployed(422, REFUSED));
        assertThat(client.contents().deploy("releases", "/held/a-1.0.jar", new byte[]{1}))
                .isEqualTo(new ContentsClient.Deployed(202, HELD));
        assertThat(client.contents().deploy("releases", "/npm-refused/a/-/a-1.0.tgz", new byte[]{1}))
                .as("an error document's message, not the document").isEqualTo(new ContentsClient.Deployed(422, REFUSED));
        assertThat(client.contents().deploy("releases", "/maven/org/acme/lib/1.0/lib-1.0.jar", new byte[]{1}))
                .as("an answer with nothing to say says nothing").isEqualTo(new ContentsClient.Deployed(201, ""));
    }

    @Test
    void deploy_streams_a_file_from_disk_byte_for_byte() throws IOException, InterruptedException {
        // The file-taking overload streams the artifact straight from disk (ofFile) rather than Files.readAllBytes-ing
        // it into heap - the stream-never-buffer upload path - and the server still receives the bytes verbatim.
        Path file = Files.writeString(work.resolve("lib-1.0.jar"), "a streamed library jar");
        int status = client.contents().deploy("releases", "/maven/org/acme/lib/1.0/lib-1.0.jar", file).status();
        assertThat(status).isEqualTo(201);
        assertThat(lastMethod).isEqualTo("PUT");
        assertThat(lastPath).isEqualTo("/repository/acme/releases/maven/org/acme/lib/1.0/lib-1.0.jar");
        assertThat(lastBody).as("the file's bytes reach the server byte-for-byte").isEqualTo("a streamed library jar");
    }

    @Test
    void deploy_explode_streams_the_archive_from_disk() throws IOException, InterruptedException {
        Path archive = Files.writeString(work.resolve("bundle.zip"), "a streamed archive");
        ContentsClient.ExplodeResult result =
                client.contents().deployExplode("releases", "/maven/org/acme/", archive);
        assertThat(lastMethod).isEqualTo("PUT");
        assertThat(lastExplode).as("the batch header still names the archive encoding").isEqualTo("zip");
        assertThat(lastBody).isEqualTo("a streamed archive");
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.manifest()).isNotNull();
        assertThat(result.manifest().entries()).hasSize(2);
    }

    @Test
    void credentials_are_parsed_over_the_nested_grants() throws IOException, InterruptedException {
        List<AccessClient.Credential> credentials = client.access().credentials();
        assertThat(credentials).hasSize(1);
        AccessClient.Credential credential = credentials.get(0);
        assertThat(credential.id()).isEqualTo("abc123");
        assertThat(credential.label()).isEqualTo("ci");
        assertThat(credential.useCount()).as("the count after the nested grants object").isEqualTo(3);
        assertThat(credential.expires()).isEqualTo("2027-01-01T00:00:00Z");
    }

    @Test
    void a_key_with_no_label_or_expiry_reads_back_empty_rather_than_absent() {
        // The bootstrap key a deployment is provisioned with has neither, and listing it threw before: a field the
        // API leaves out arrived as null and the listing called isEmpty() on it.
        AccessClient.Credential bare = new AccessClient.Credential("abc123", null, null, 0);
        assertThat(bare.label()).isEmpty();
        assertThat(bare.expires()).isEmpty();
        assertThat(new AccessClient.Minted("abc123", "key", null).expires()).isEmpty();
    }

    @Test
    void repository_definitions_are_listed_set_and_removed() throws IOException, InterruptedException {
        List<SettingsClient.NamedValue> repos = client.settings().repositories(null);
        assertThat(repos).hasSize(1);
        assertThat(repos.get(0).name()).isEqualTo("mirror");
        assertThat(repos.get(0).value()).isEqualTo("fallback https://repo1.maven.org/maven2/");

        client.settings().setRepository(null, "mirror", "writable");
        assertThat(lastMethod).isEqualTo("PUT");
        assertThat(lastPath).isEqualTo("/api/repositories/mirror");
        assertThat(lastBody).isEqualTo("{\"value\":\"writable\"}");

        client.settings().removeRepository(null, "mirror");
        assertThat(lastMethod).isEqualTo("DELETE");
        assertThat(lastPath).isEqualTo("/api/repositories/mirror");
    }

    @Test
    void format_upstreams_are_listed_and_set() throws IOException, InterruptedException {
        List<SettingsClient.NamedValue> upstreams = client.settings().upstreams(null);
        assertThat(upstreams).hasSize(1);
        assertThat(upstreams.get(0).name()).isEqualTo("npm");
        assertThat(upstreams.get(0).value()).isEqualTo("https://npm.internal/");

        client.settings().setUpstream(null, "npm", "https://npm.internal/");
        assertThat(lastPath).isEqualTo("/api/upstreams/npm");
        assertThat(lastBody).isEqualTo("{\"value\":\"https://npm.internal/\"}");
    }

    @Test
    void upstream_credentials_are_listed_and_set_write_only() throws IOException, InterruptedException {
        assertThat(client.settings().upstreamCredentialHosts()).containsExactly("nexus.internal");

        client.settings().setUpstreamCredential("nexus.internal", "bearer", null, null, "s3cr3t", null);
        assertThat(lastMethod).isEqualTo("PUT");
        assertThat(lastPath).isEqualTo("/api/upstreams/auth/nexus.internal");
        assertThat(lastBody).isEqualTo("{\"scheme\":\"bearer\",\"token\":\"s3cr3t\"}");

        client.settings().setUpstreamCredential("api.host", "header", null, null, "k3y", "X-Api-Key");
        assertThat(lastPath).isEqualTo("/api/upstreams/auth/api.host");
        assertThat(lastBody).as("a custom header carries its name")
                .isEqualTo("{\"scheme\":\"header\",\"token\":\"k3y\",\"header\":\"X-Api-Key\"}");
    }

    @Test
    void mint_posts_the_label_and_returns_the_secret_once() throws IOException, InterruptedException {
        AccessClient.Minted minted = client.access().mint("ci");
        assertThat(lastMethod).isEqualTo("POST");
        assertThat(lastPath).isEqualTo("/api/credentials");
        assertThat(lastBody).isEqualTo("{\"label\":\"ci\"}");
        assertThat(minted.id()).isEqualTo("def456");
        assertThat(minted.key()).as("the secret shown once").isEqualTo("jenk_acme.secret");
    }

    @Test
    void the_license_inventory_is_parsed_into_its_state_and_counts_of_versions()
            throws IOException, InterruptedException {
        RiskClient.LicensesView view = client.risk().licenses("releases");
        assertThat(lastPath).isEqualTo("/api/licenses");
        assertThat(lastQuery).contains("repo=releases").doesNotContain("refresh");
        assertThat(view.state()).isEqualTo("done");
        assertThat(view.finishedAt()).isEqualTo("2026-01-01T00:00:05Z");
        assertThat(view.versions()).isEqualTo(15);
        assertThat(view.categories()).extracting(RiskClient.LicenseCount::value)
                .containsExactly("permissive", "strong-copyleft");
        assertThat(view.licenses()).first().satisfies(count -> {
            assertThat(count.value()).isEqualTo("Apache-2.0");
            assertThat(count.versions()).isEqualTo(8);
        });
        assertThat(view.truncated()).isFalse();

        RiskClient.LicenseCountStart start = client.risk().countLicenses("releases");
        assertThat(lastQuery).as("a count is asked for on the read itself").contains("refresh=true");
        assertThat(start.inventory().state()).isEqualTo("done");
    }

    @Test
    void quarantine_holds_are_listed_and_reviewed() throws IOException, InterruptedException {
        List<ReviewClient.QuarantineEvent> events = client.review().quarantine("releases", null).events();
        assertThat(lastPath).isEqualTo("/api/quarantine");
        assertThat(lastQuery).contains("repo=releases");
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.verdict()).isEqualTo("QUARANTINE");
            assertThat(event.path()).isEqualTo("/maven/org/acme/lib/1.0/lib-1.0.jar");
            assertThat(event.reasons()).containsExactly("unsigned", "license unknown");
        });

        client.review().releaseQuarantine("releases",
                List.of("/maven/org/acme/lib/1.0/lib-1.0.jar", "/maven/org/acme/lib/1.0/lib-1.0.pom"));
        assertThat(lastMethod).isEqualTo("POST");
        assertThat(lastPath).isEqualTo("/api/quarantine/release");
        assertThat(lastQuery).as("the repo rides the query, the paths the body").contains("repo=releases");
        assertThat(lastBody).as("a version's files in one request")
                .isEqualTo("{\"paths\":[\"/maven/org/acme/lib/1.0/lib-1.0.jar\",\"/maven/org/acme/lib/1.0/lib-1.0.pom\"]}");
        assertThat(lastKey).isEqualTo("jenk_acme.secret");

        ReviewClient.Discarded discarded =
                client.review().discardQuarantine("releases", List.of("/maven/org/acme/lib/1.0/lib-1.0.jar"));
        assertThat(lastPath).isEqualTo("/api/quarantine/discard");
        assertThat(discarded.discarded()).containsExactly("/maven/org/acme/lib/1.0/lib-1.0.jar");
        assertThat(discarded.absent()).isEmpty();
    }

    @Test
    void the_forwarding_outbox_is_listed_and_a_parked_forward_is_retried() throws IOException, InterruptedException {
        List<LifecycleClient.ForwardingEntry> entries = client.lifecycle().forwarding("mirror", null).items();
        assertThat(lastPath).isEqualTo("/api/forwarding");
        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.ecosystem()).isEqualTo("npm");
            assertThat(entry.status()).isEqualTo("parked");
            assertThat(entry.parked()).isTrue();
            assertThat(entry.attempts()).isEqualTo(3);
            assertThat(entry.delivered()).isEqualTo(1);
            assertThat(entry.error()).isEqualTo("502 from mirror");
        });

        assertThat(client.lifecycle().retryForwarding("mirror", "/npm/left-pad/-/left-pad-1.0.0.tgz"))
                .as("a 200 means the parked forward was unparked").isTrue();
        assertThat(lastMethod).isEqualTo("POST");
        assertThat(lastPath).isEqualTo("/api/forwarding/retry");
        assertThat(lastBody).isEqualTo("{\"path\":\"/npm/left-pad/-/left-pad-1.0.0.tgz\"}");
    }

    @Test
    void staging_ids_are_listed_promoted_and_dropped() throws IOException, InterruptedException {
        List<ContentsClient.StagingEntry> entries = client.contents().staging("releases");
        assertThat(lastPath).isEqualTo("/api/repository/staging");
        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.id()).isEqualTo("stg-abc");
            assertThat(entry.state()).isEqualTo("OPEN");
            assertThat(entry.items()).isEqualTo(4);
        });

        assertThat(client.contents().promoteStaging("releases", "stg-abc")).isEqualTo(200);
        assertThat(lastMethod).isEqualTo("POST");
        assertThat(lastPath).isEqualTo("/api/repository/staging/stg-abc/promote");
        assertThat(lastQuery).isEqualTo("repo=releases");
        assertThat(lastKey).isEqualTo("jenk_acme.secret");

        assertThat(client.contents().dropStaging("releases", "stg-abc")).isEqualTo(200);
        assertThat(lastPath).isEqualTo("/api/repository/staging/stg-abc/drop");
    }

    @Test
    void the_published_index_descriptor_is_parsed_over_its_chunks() throws IOException, InterruptedException {
        ContentsClient.IndexDescriptor descriptor = client.contents().index("releases");
        assertThat(lastPath).isEqualTo("/api/index");
        assertThat(lastQuery).contains("repo=releases");
        assertThat(descriptor.generation()).isEqualTo(7);
        assertThat(descriptor.watermark()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(descriptor.chunks()).singleElement().satisfies(chunk -> {
            assertThat(chunk.records()).isEqualTo(9);
            assertThat(chunk.compressedSize()).isEqualTo(40);
        });
    }

    @Test
    void the_asset_walk_is_paged_by_an_opaque_cursor() throws IOException, InterruptedException {
        ContentsClient.AssetPage first = client.contents().assets("releases", null, 2);
        assertThat(lastPath).isEqualTo("/api/assets");
        assertThat(lastQuery).contains("repo=releases").contains("limit=2");
        assertThat(first.assets()).singleElement().satisfies(asset -> {
            assertThat(asset.path()).isEqualTo("/maven/org/acme/lib/1.0/lib-1.0.jar");
            assertThat(asset.size()).isEqualTo(13);
            assertThat(asset.sha256()).isEqualTo("abc123");
            assertThat(asset.coordinate()).isEqualTo("org.acme:lib");
        });
        assertThat(first.next()).as("a full page carries a resume cursor").isEqualTo("bWF2ZW4");

        // The cursor is threaded back as ?after= to fetch the next page, which exhausts the walk (cursor null).
        ContentsClient.AssetPage second = client.contents().assets("releases", first.next(), 2);
        assertThat(lastQuery).contains("after=bWF2ZW4");
        assertThat(second.assets()).extracting(ContentsClient.AssetEntry::path)
                .containsExactly("/npm/left-pad/-/left-pad-1.3.0.tgz");
        assertThat(second.next()).as("the exhausted walk carries no cursor").isNull();
    }

    @Test
    void settings_can_be_scoped_to_a_tenant() throws IOException, InterruptedException {
        List<SettingsClient.Setting> settings = client.settings().settings("acme");
        assertThat(lastPath).isEqualTo("/api/settings");
        assertThat(lastQuery).as("the tenant scopes the read").contains("tenant=acme");
        assertThat(settings).hasSize(3);

        client.settings().setSetting("acme", "license-allowed", "apache,mit");
        assertThat(lastMethod).isEqualTo("PUT");
        assertThat(lastPath).isEqualTo("/api/settings/license-allowed");
        assertThat(lastQuery).contains("tenant=acme");
        assertThat(lastBody).isEqualTo("{\"value\":\"apache,mit\"}");

        client.settings().clearSetting("acme", "proxy-enabled");
        assertThat(lastMethod).isEqualTo("DELETE");
        assertThat(lastPath).isEqualTo("/api/settings/proxy-enabled");
        assertThat(lastQuery).contains("tenant=acme");

        String bundle = client.settings().exportSettings("acme");
        assertThat(lastPath).isEqualTo("/api/settings/export");
        assertThat(lastQuery).contains("tenant=acme");
        assertThat(bundle).isEqualTo(SETTINGS_BUNDLE);

        client.settings().importSettings("acme", bundle);
        assertThat(lastMethod).isEqualTo("POST");
        assertThat(lastPath).isEqualTo("/api/settings/import");
        assertThat(lastQuery).contains("tenant=acme");
        assertThat(lastBody).as("the tenant slice is posted back verbatim").isEqualTo(SETTINGS_BUNDLE);
    }

    @Test
    void capabilities_carry_the_module_list() throws IOException, InterruptedException {
        RepositoryClient.Capabilities capabilities = client.capabilities();
        assertThat(lastPath).isEqualTo("/api/capabilities");
        assertThat(capabilities.modules()).extracting(RepositoryClient.Module::module)
                .containsExactly("build.jenesis.repository.search", "build.jenesis.repository.audit.store");
        RepositoryClient.Module search = capabilities.modules().get(0);
        assertThat(search.installed()).isTrue();
        assertThat(search.enableKey()).isEqualTo("search");
        assertThat(search.enabled()).isTrue();
        assertThat(search.live()).as("a restart-only toggle").isFalse();
        assertThat(capabilities.modules().get(1).installed()).as("a leftover stored document").isFalse();
        assertThat(capabilities.search()).as("the search feature flag, contributed at the top level").isTrue();
    }

    @Test
    void the_quota_is_read_and_set() throws IOException, InterruptedException {
        SettingsClient.QuotaView view = client.settings().quota();
        assertThat(lastPath).isEqualTo("/api/quota");
        assertThat(view.maxBytes()).isEqualTo(1073741824L);
        assertThat(view.usedBytes()).isEqualTo(2048L);

        client.settings().setQuota(null, 500L);
        assertThat(lastMethod).isEqualTo("PUT");
        assertThat(lastBody).isEqualTo("{\"maxBytes\":500}");
    }

    @Test
    void the_rate_limit_and_policy_are_read_and_set() throws IOException, InterruptedException {
        assertThat(client.settings().rateLimit().permitsPerMinute()).isEqualTo(600L);
        client.settings().setRateLimit(null, 120L);
        assertThat(lastPath).isEqualTo("/api/rate-limit");
        assertThat(lastBody).isEqualTo("{\"permitsPerMinute\":120}");

        AccessClient.PolicyView policy = client.access().policy();
        assertThat(policy.defaultLifetime()).isEqualTo("P90D");
        assertThat(policy.maxLifetime()).isEqualTo("P365D");
        client.access().setPolicy("P30D", null);
        assertThat(lastPath).isEqualTo("/api/policy");
        assertThat(lastBody).as("an omitted --max clears the ceiling (full-replace endpoint)")
                .isEqualTo("{\"defaultLifetime\":\"P30D\",\"maxLifetime\":\"\"}");
    }

    @Test
    void roles_are_parsed_into_a_map_and_set() throws IOException, InterruptedException {
        Map<String, String> roles = client.access().roles();
        assertThat(lastPath).isEqualTo("/api/roles");
        assertThat(roles).containsEntry("read-only", "repository:read")
                .containsEntry("deploy", "repository:read,repository:write");

        client.access().setRole("ci", "repository:read,repository:write");
        assertThat(lastMethod).isEqualTo("PUT");
        assertThat(lastPath).isEqualTo("/api/roles/ci");
        assertThat(lastBody).isEqualTo("{\"tokens\":\"repository:read,repository:write\"}");
    }

    @Test
    void trusts_are_listed_and_set_over_their_fields() throws IOException, InterruptedException {
        assertThat(client.access().trusts()).singleElement().satisfies(trust -> {
            assertThat(trust.name()).isEqualTo("ci");
            assertThat(trust.issuer()).isEqualTo("https://token.actions.githubusercontent.com");
            assertThat(trust.scope()).isEqualTo("releases");
            assertThat(trust.rights()).isEqualTo("deploy");
        });

        client.access().setTrust("ci", "https://issuer", null, null, "releases", "deploy", "PT1H");
        assertThat(lastMethod).isEqualTo("PUT");
        assertThat(lastPath).isEqualTo("/api/trusts/ci");
        assertThat(lastBody).as("a null audience/subject is omitted from the body")
                .isEqualTo("{\"issuer\":\"https://issuer\",\"scope\":\"releases\",\"rights\":\"deploy\",\"ttl\":\"PT1H\"}");
    }

    @Test
    void the_audit_trail_is_filtered_and_parsed() throws IOException, InterruptedException {
        List<AccessClient.AuditEvent> events = client.access().audit("2026-01-01T00:00:00Z", null, "credential.mint",
                null).items();
        assertThat(lastPath).isEqualTo("/api/audit");
        assertThat(lastQuery).contains("from=").contains("action=credential.mint");
        assertThat(lastQuery).as("a null bound is not sent").doesNotContain("to=");
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.actor()).isEqualTo("ci");
            assertThat(event.action()).isEqualTo("credential.mint");
        });
    }

    @Test
    void cleanup_retention_and_pins_are_driven() throws IOException, InterruptedException {
        LifecycleClient.CleanupReport report = client.lifecycle().cleanup("releases").report();
        assertThat(lastMethod).isEqualTo("POST");
        assertThat(lastPath).isEqualTo("/api/repository/cleanup");
        assertThat(report.gc().collected()).isEqualTo(3);
        assertThat(report.evicted()).containsExactly("org.acme:lib:0.9 - superseded");

        LifecycleClient.RetentionView view = client.lifecycle().retention("releases");
        assertThat(view.keepLast()).isEqualTo(5);
        assertThat(view.maxAge()).isEqualTo("P30D");

        client.lifecycle().setRetention("releases", "3", "P30D", null, null);
        assertThat(lastMethod).isEqualTo("PUT");
        assertThat(lastPath).isEqualTo("/api/repository/retention");
        assertThat(lastQuery).contains("repo=releases").contains("keepLast=3").contains("maxAge=P30D")
                .as("a rule not given is not sent, so it stays as it is").doesNotContain("prereleaseExpiry");

        assertThat(client.lifecycle().pins("releases")).containsExactly("Maven:org.acme:lib:1.0");

        client.lifecycle().pin("releases", "Maven", "org.acme:lib", "1.0");
        assertThat(lastMethod).isEqualTo("POST");
        assertThat(lastPath).isEqualTo("/api/repository/pin");
        assertThat(lastQuery).contains("ecosystem=Maven").contains("coordinate=org.acme").contains("version=1.0");

        client.lifecycle().unpin("releases", "Maven", "org.acme:lib", "1.0");
        assertThat(lastMethod).isEqualTo("DELETE");
        assertThat(lastPath).isEqualTo("/api/repository/pin");
    }

    @Test
    void the_orphan_report_and_the_purge_are_driven() throws IOException, InterruptedException {
        SettingsClient.OrphansView orphans = client.settings().orphans();
        assertThat(lastMethod).isEqualTo("GET");
        assertThat(lastPath).isEqualTo("/api/admin/orphans");
        assertThat(orphans.orphans()).hasSize(1);
        assertThat(orphans.orphans().getFirst().namespace()).isEqualTo("build.jenesis.repository.phantom");
        assertThat(orphans.orphans().getFirst().objects()).isEqualTo(3);

        SettingsClient.PurgeReport plan = client.settings().purge("build.jenesis.repository.phantom", true);
        assertThat(lastMethod).isEqualTo("POST");
        assertThat(lastPath).isEqualTo("/api/admin/purge");
        assertThat(lastQuery).contains("namespace=build.jenesis.repository.phantom").contains("dryRun=true");
        assertThat(plan.dryRun()).isTrue();
        assertThat(plan.spaces()).extracting(SettingsClient.PurgeSpace::prefix)
                .containsExactly("default/releases/phantomspace");
        assertThat(plan.objects()).isEqualTo(3);

        assertThat(client.settings().purge("no.such.module", false))
                .as("an unregistered namespace answers 404 -> null").isNull();
    }

    @Test
    void the_retro_license_plan_is_parsed() throws IOException, InterruptedException {
        RiskClient.RetroPlan plan = client.risk().retroPlan("releases", true);
        assertThat(lastPath).isEqualTo("/api/licenses/retro/plan");
        assertThat(lastQuery).contains("repo=releases").contains("unknown=true");
        assertThat(plan.mode()).isEqualTo("denied");
        assertThat(plan.count()).isEqualTo(2);
        assertThat(plan.held()).singleElement().satisfies(held -> {
            assertThat(held.coordinate()).isEqualTo("org.gnu:x");
            assertThat(held.reasons()).containsExactly("GPL-3.0 denied");
        });
    }

    @Test
    void the_credential_sub_ops_hit_their_keyed_paths() throws IOException, InterruptedException {
        client.access().setGrant("abc123", "releases", List.of("repository:read", "repository:write"));
        assertThat(lastMethod).isEqualTo("POST");
        assertThat(lastPath).isEqualTo("/api/credentials/abc123/grants");
        assertThat(lastBody).isEqualTo(
                "{\"scope\":\"releases\",\"tokens\":[\"repository:read\",\"repository:write\"]}");

        client.access().removeGrant("abc123", "releases");
        assertThat(lastMethod).isEqualTo("DELETE");
        assertThat(lastPath).isEqualTo("/api/credentials/abc123/grants/releases");

        client.access().setExpiry("abc123", "P30D");
        assertThat(lastMethod).isEqualTo("PUT");
        assertThat(lastPath).isEqualTo("/api/credentials/abc123/expiry");
        assertThat(lastBody).isEqualTo("{\"expires\":\"P30D\"}");

        client.access().setAllowedAddresses("abc123", "203.0.113.0/24");
        assertThat(lastPath).isEqualTo("/api/credentials/abc123/allowed-ips");
        assertThat(lastBody).isEqualTo("{\"addresses\":\"203.0.113.0/24\"}");

        AccessClient.Minted rotated = client.access().rotate("abc123", "P2D");
        assertThat(lastMethod).isEqualTo("POST");
        assertThat(lastPath).isEqualTo("/api/credentials/abc123/rotate");
        assertThat(lastBody).isEqualTo("{\"overlap\":\"P2D\"}");
        assertThat(rotated.id()).isEqualTo("newid789");
        assertThat(rotated.key()).as("the successor secret shown once").isEqualTo("jenk_acme.rotated");
    }

    @Test
    void provenance_material_and_key_are_fetched() throws IOException, InterruptedException {
        ProvenanceClient.ProvenanceMaterial material =
                client.provenance().provenanceMaterial("releases", "/maven/org/acme/lib/1.0/lib-1.0.pom");
        assertThat(lastPath).isEqualTo("/api/provenance");
        assertThat(lastQuery).contains("material");
        assertThat(material.certificateChain()).isEqualTo("-----BEGIN CERTIFICATE-----");
        assertThat(material.transparencyLog()).isNotNull();
        assertThat(material.transparencyLog().logIndex()).isEqualTo(42L);
        assertThat(material.transparencyLog().inclusionProof().treeSize()).isEqualTo(100L);

        assertThat(client.provenance().provenanceKey()).isEqualTo(PROV_KEY);
        assertThat(lastPath).isEqualTo("/api/provenance/key");
    }

    @Test
    void an_import_is_started_and_polled() throws IOException, InterruptedException {
        ContentsClient.ImportResult result = client.contents().startImport("releases", "nexus",
                "https://nexus.internal/", "maven-releases", "maven", null, null, null);
        assertThat(lastMethod).isEqualTo("POST");
        assertThat(lastPath).isEqualTo("/api/repository/import");
        assertThat(lastBody).as("the source, url, source repository and format are posted")
                .contains("\"source\":\"nexus\"").contains("\"url\":\"https://nexus.internal/\"")
                .contains("\"repository\":\"maven-releases\"").contains("\"format\":\"maven\"");
        assertThat(result.status()).isEqualTo(202);
        assertThat(result.job()).isEqualTo("job-123");

        ContentsClient.ImportStatus status = client.contents().importStatus("releases", "job-123");
        assertThat(lastPath).isEqualTo("/api/repository/import/job-123");
        assertThat(status.imported()).isEqualTo(10);
        assertThat(status.skipped()).isEqualTo(2);
        assertThat(status.skippedFormats()).containsExactly("cocoapods");
    }

    @Test
    void deploy_explode_ignores_a_json_error_body_so_the_caller_degrades_gracefully()
            throws IOException, InterruptedException {
        // A real server error (403 write-denied, 500) renders a JSON body like {"status":403,...} that starts with
        // '{'. It must NOT be mistaken for a batch manifest (its unknown fields parse into a manifest with a null
        // entries), or the caller NPEs walking a null entry list instead of printing "explode failed (HTTP 403)".
        ContentsClient.ExplodeResult result =
                client.contents().deployExplode("releases", "/maven/errorbody/", "an archive".getBytes(UTF_8));
        assertThat(lastExplode).isEqualTo("zip");
        assertThat(result.status()).isEqualTo(403);
        assertThat(result.manifest()).as("a framework error body is not a batch manifest").isNull();
    }

    @Test
    void deploy_explode_sets_the_header_and_parses_the_manifest() throws IOException, InterruptedException {
        ContentsClient.ExplodeResult result =
                client.contents().deployExplode("releases", "/maven/org/acme/", "an archive".getBytes(UTF_8));
        assertThat(lastMethod).isEqualTo("PUT");
        assertThat(lastPath).isEqualTo("/repository/acme/releases/maven/org/acme/");
        assertThat(lastExplode).as("the batch header names the archive encoding").isEqualTo("zip");
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.manifest()).isNotNull();
        assertThat(result.manifest().entries()).hasSize(2);
        assertThat(result.manifest().entries().get(0).status()).isEqualTo("stored");
        assertThat(result.manifest().entries().get(1).status()).isEqualTo("rejected");
        assertThat(result.manifest().entries().get(1).reason()).isEqualTo("license denied");
    }
}
