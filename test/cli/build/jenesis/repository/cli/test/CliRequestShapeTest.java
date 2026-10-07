package build.jenesis.repository.cli.test;

import module java.base;
import module org.junit.jupiter.api;
import module tools.jackson.databind;
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
 * Every action sends the request its endpoint binds: the method, the path, exactly the query parameters the endpoint
 * reads, and a body only where the endpoint reads one - with exactly the fields it reads.
 *
 * <p><b>What this catches that {@code CliBindingTest} does not.</b> That suite asks whether a command reaches its
 * endpoint's path, and a request of the wrong shape reaches the right path just as well: a JSON document posted to a
 * route that binds only query parameters, an id put in the query of a route that reads it from the body, a
 * documented dry run whose argument order makes it the sweep itself. Each of those is a {@code 400} - or worse, a
 * write - from the real server behind a green binding census. A parameter the endpoint does not bind is the quiet
 * form of the same defect: sent, ignored, and read by the caller as a narrowing that never happened, which is why
 * build scans, test runs and VEX documents, the tenant's rather than a repository's, take no repository here.
 *
 * <p><b>Where the expected shapes come from.</b> Each case states the request as the endpoint's own declaration
 * binds it - its {@code @RequestParam} names, its {@code @RequestBody} record's fields, or the raw document it reads
 * from the request stream - so the API is the contract and the command line follows it. The stand-in server answers
 * everything alike and records what arrived; a command may fail afterwards on a reply it cannot parse, which is not
 * this suite's subject.
 *
 * <p><b>Why every action, not every noun.</b> The census below fails when an action form in {@code Commands} has no
 * case here. A noun's read and its writes are different requests to different routes, and a write behind a noun
 * whose read is covered is exactly the request nothing else looks at.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CliRequestShapeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A request as the endpoint binds it. {@code json} names the body's fields, {@code null} when the endpoint
     *  reads no JSON document; {@code raw} is a file's content forwarded as it is. */
    private record Sent(String method, String path, Set<String> query, Set<String> json, boolean raw) {

        Sent body(String... fields) {
            return new Sent(method, path, query, new TreeSet<>(List.of(fields)), false);
        }

        Sent file() {
            return new Sent(method, path, query, null, true);
        }

        @Override
        public String toString() {
            return method + " " + path + " query=" + query
                    + (raw ? " body=<the file>" : json == null ? " no body" : " json=" + json);
        }
    }

    private static Sent get(String path, String... query) {
        return new Sent("GET", path, new TreeSet<>(List.of(query)), null, false);
    }

    private static Sent post(String path, String... query) {
        return new Sent("POST", path, new TreeSet<>(List.of(query)), null, false);
    }

    private static Sent put(String path, String... query) {
        return new Sent("PUT", path, new TreeSet<>(List.of(query)), null, false);
    }

    private static Sent delete(String path, String... query) {
        return new Sent("DELETE", path, new TreeSet<>(List.of(query)), null, false);
    }

    /** One action: the command line that runs it, and every request it makes, in order. */
    private record Case(String line, List<Sent> requests) {
    }

    private static Map.Entry<String, Case> action(String form, String line, Sent... requests) {
        return Map.entry(form, new Case(line, List.of(requests)));
    }

    /** The nouns that never call the server: they read or write the stored session on disk. */
    private static final Set<String> LOCAL = Set.of("login", "logout", "whoami");

    /** A path in the default tenant the test key addresses, as the data plane names it. */
    private static final String REPOSITORY = "/repository/releases/releases";

    private static final Map<String, Case> ACTIONS = Map.ofEntries(
            // Contents
            action("browse <repo> [prefix]", "browse releases /maven", get("/api/browse", "repo", "prefix")),
            action("browse children <repo> [prefix] [--limit N] [--cursor T]",
                    "browse children releases /maven --limit 5 --cursor org",
                    get("/api/browse/children", "repo", "prefix", "limit", "after")),
            action("search <repo> [query]", "search releases acme", get("/api/search", "repo", "q")),
            action("assets <repo> [--limit N] [--cursor T] [--all]", "assets releases --limit 5 --cursor c",
                    get("/api/assets", "repo", "limit", "cursor")),
            action("deploy <repo> <path> <file> [--explode zip]", "deploy releases /a/b.jar @FILE",
                    put(REPOSITORY + "/a/b.jar").file()),
            action("import <repo> --source S --url U --source-repo R [--format F]"
                            + " [--user U --password P] [--resume JOB]",
                    "import releases --source nexus --url http://x/ --source-repo r --format maven",
                    post("/api/repository/import", "repo").body("source", "url", "repository", "format")),
            action("import status <repo> <job>", "import status releases j1",
                    get("/api/repository/import/j1", "repo")),
            action("staging <repo>", "staging releases", get("/api/repository/staging", "repo")),
            action("staging promote <repo> <id>", "staging promote releases s1",
                    post("/api/repository/staging/s1/promote", "repo")),
            action("staging drop <repo> <id>", "staging drop releases s1",
                    post("/api/repository/staging/s1/drop", "repo")),
            action("index <repo>", "index releases", get("/api/index", "repo")),

            // Review
            action("quarantine <repo> [--cursor C]", "quarantine releases --cursor c",
                    get("/api/quarantine", "repo", "after")),
            action("quarantine hold <repo> <ecosystem> <coordinate> <version>",
                    "quarantine hold releases Maven org.acme:lib 1.0",
                    post("/api/quarantine/hold", "repo").body("ecosystem", "coordinate", "version")),
            action("quarantine release <repo> <path>...", "quarantine release releases /a/b.jar /a/b.pom",
                    post("/api/quarantine/release", "repo").body("paths")),
            action("quarantine discard <repo> <path>...", "quarantine discard releases /a/b.jar /a/b.pom",
                    post("/api/quarantine/discard", "repo").body("paths")),
            action("ai-review <repo> [--cursor C]", "ai-review releases --cursor 500",
                    get("/api/findings", "repo", "kind", "offset")),

            // Risk
            action("vulnerabilities <repo> [--reachability reachable|not-reachable|unknown]"
                            + " [--applicability applies|not-applicable|unknown]",
                    "vulnerabilities releases --reachability reachable --applicability applies",
                    get("/api/vulnerabilities", "repo", "reachability", "applicability")),
            action("vulnerabilities rescan <repo>", "vulnerabilities rescan releases",
                    get("/api/vulnerabilities", "repo", "refresh"), get("/api/vulnerabilities", "repo")),
            action("findings <repo> [--ecosystem E] [--coordinate C] [--kind K] [--source S] [--category C] [--severity S]"
                            + " [--cursor C]",
                    "findings releases --ecosystem npm --coordinate c --kind k --source s --category g --severity HIGH"
                            + " --cursor 500",
                    get("/api/findings", "repo", "ecosystem", "coordinate", "kind", "source", "category", "severity",
                            "offset")),
            action("findings review <repo> <ecosystem> <coordinate> <version> <source> <id>"
                            + " <confirmed|dismissed> [--note N]",
                    "findings review releases Maven org.acme:lib 1.0 audit GHSA-x confirmed --note seen",
                    post("/api/findings/review", "repo", "ecosystem", "coordinate", "version", "source", "id",
                            "decision", "note")),
            action("findings waiver <repo> <ecosystem> <coordinate> <version> <source> <id> <until> [--note N]",
                    "findings waiver releases Maven org.acme:lib 1.0 osv GHSA-x 2099-01-01T00:00:00Z --note accepted",
                    post("/api/findings/waiver", "repo", "ecosystem", "coordinate", "version", "source", "id",
                            "expires", "note")),
            action("findings waiver revoke <repo> <ecosystem> <coordinate> <version> <source> <id>",
                    "findings waiver revoke releases Maven org.acme:lib 1.0 osv GHSA-x",
                    post("/api/findings/waiver/revoke", "repo", "ecosystem", "coordinate", "version", "source",
                            "id")),
            action("findings report <repo> <file>", "findings report releases @FILE",
                    post("/api/findings/report", "repo").file()),
            action("health <repo> [--cursor C]", "health releases --cursor c", get("/api/health", "repo", "after")),
            action("health refresh <repo>", "health refresh releases", get("/api/health", "repo", "refresh")),
            action("enforcement-preview <repo> [--unknown]", "enforcement-preview releases --unknown",
                    get("/api/licenses/retro/plan", "repo", "unknown")),
            action("licenses <repo> [--count]", "licenses releases --count",
                    get("/api/licenses", "repo", "refresh")),
            action("hardening <repo> [path]", "hardening releases /a/b.jar",
                    get("/api/hardening/verdict", "repo", "path")),

            // Provenance
            action("signers <repo> [--cursor C]", "signers releases --cursor c", get("/api/signers", "repo", "after")),
            action("signers <repo> <signer> [--cursor C]", "signers releases pgp:abc --cursor c",
                    get("/api/signers/signed", "repo", "signer", "after")),
            action("signature <repo> <path>", "signature releases /a/b.jar", get("/api/signature", "repo", "path")),
            action("closure <repo> <ecosystem> <coordinate> <version>", "closure releases Maven org.acme:app 1.0",
                    get("/api/repository/closure", "repo", "ecosystem", "coordinate", "version")),
            action("provenance <repo> <path> [--material]", "provenance releases /a/b.jar --material",
                    get("/api/provenance", "repo", "path", "material")),
            action("provenance key", "provenance key", get("/api/provenance/key")),
            action("provenance cert", "provenance cert", get("/api/provenance/certificate")),
            action("dependents <repo> <ecosystem> <coordinate> [--version V] [--cursor T] [--declared-cursor T]",
                    "dependents releases npm lodash --version 4.0.0 --cursor c --declared-cursor d",
                    get("/api/repository/dependents", "repo", "ecosystem", "coordinate", "version", "after",
                            "declaredAfter")),
            action("sbom <repo> [path] [--format cyclonedx|cyclonedx-xml|spdx] [--output F]",
                    "sbom releases /a/b.jar --format spdx", get("/api/sbom", "repo", "path", "format")),
            action("origin <repo> [path]", "origin releases /a", get("/api/origin", "repo", "path")),
            action("attribution <repo> [--coordinate C] [--format F]",
                    "attribution releases --coordinate org.acme:lib --format text",
                    get("/api/attribution", "repo", "coordinate", "format")),
            action("vex [--cursor C]", "vex --cursor c", get("/api/vex", "after")),
            action("vex show <id>", "vex show v1", get("/api/vex/v1")),
            action("vex add <file>", "vex add @FILE", post("/api/vex").file()),
            action("vex remove <id>", "vex remove v1", delete("/api/vex/v1")),
            action("vex export [--output F]", "vex export", get("/api/vex/export")),

            // Lifecycle
            action("retention <repo>", "retention releases", get("/api/repository/retention", "repo")),
            action("retention set <repo> [--keep-last N] [--max-age D] [--prerelease-expiry D]"
                            + " [--not-downloaded-for D]", "retention set releases --keep-last 3 --max-age none",
                    put("/api/repository/retention", "repo", "keepLast", "maxAge")),
            action("cleanup <repo>", "cleanup releases", post("/api/repository/cleanup", "repo")),
            action("cleanup plan <repo>", "cleanup plan releases", get("/api/repository/cleanup/plan", "repo")),
            action("pins <repo>", "pins releases", get("/api/repository/pins", "repo")),
            action("pins pin <repo> <ecosystem> <coordinate> <version>", "pins pin releases Maven org.acme:lib 1.0",
                    post("/api/repository/pin", "repo", "ecosystem", "coordinate", "version")),
            action("pins unpin <repo> <ecosystem> <coordinate> <version>",
                    "pins unpin releases Maven org.acme:lib 1.0",
                    delete("/api/repository/pin", "repo", "ecosystem", "coordinate", "version")),
            action("lifecycle <repo>", "lifecycle releases", get("/api/lifecycle", "repository")),
            action("lifecycle mark <repo> <coordinate> <version> <state> [--message T]",
                    "lifecycle mark releases org.acme:lib 1.0 deprecated --message upgrade",
                    post("/api/lifecycle", "repository", "coordinate", "version", "state", "message")),
            action("lifecycle clear <repo> <coordinate> <version>", "lifecycle clear releases org.acme:lib 1.0",
                    delete("/api/lifecycle", "repository", "coordinate", "version")),
            action("forwarding <repo> [--cursor C]", "forwarding releases --cursor c",
                    get("/api/forwarding", "repo", "after")),
            action("forwarding retry <repo> <path>", "forwarding retry releases /a/b.jar",
                    post("/api/forwarding/retry", "repo").body("path")),
            action("forwarding internal <repo> <dest-tenant> <dest-repo>", "forwarding internal releases acme libs",
                    post("/api/forwarding/internal", "sourceRepo", "destTenant", "destRepo")),
            action("forwarding internal remove <repo> <dest-tenant> <dest-repo>",
                    "forwarding internal remove releases acme libs",
                    delete("/api/forwarding/internal", "sourceRepo", "destTenant", "destRepo")),
            action("webhook <repo> [--cursor C]", "webhook releases --cursor c", get("/api/webhook", "repo", "after")),
            action("webhook retry <repo> <id>", "webhook retry releases w1",
                    post("/api/webhook/retry", "repo").body("id")),
            action("export <repo> --url U [--token T | --user U --password P] [--resume JOB]",
                    "export releases --url http://x/ --token t",
                    post("/api/repository/export", "repo").body("url", "token")),
            action("export status <repo> <job>", "export status releases j1",
                    get("/api/repository/export/j1", "repo")),
            action("forget-ecosystem <repo> <ecosystem>", "forget-ecosystem releases npm",
                    post("/api/repository/forget-ecosystem", "repo", "ecosystem")),

            // Build cache
            action("projects", "projects", get("/api/cache/projects")),
            action("projects show <project>", "projects show agents", get("/api/cache/projects/agents")),
            action("projects create <project> <type> [description] [--set <key>=<value>]...",
                    "projects create agents gradle CI agents --set project-size=1048576",
                    post("/api/cache/projects", "name", "type").body("description", "settings")),
            action("projects describe <project> [description]", "projects describe agents CI agents",
                    put("/api/cache/projects/agents/description").body("description")),
            action("projects evict <project> <size|ttl|clear>", "projects evict agents size",
                    post("/api/cache/projects/agents/evict/size")),
            action("projects recount <project>", "projects recount agents",
                    post("/api/cache/projects/agents/recount")),
            action("projects settings <project>", "projects settings agents",
                    get("/api/cache/projects/agents/settings")),
            action("projects settings <project> set <key> <value>",
                    "projects settings agents set project-size 1048576",
                    put("/api/cache/projects/agents/settings/project-size").body("value")),
            action("projects settings <project> clear <key>", "projects settings agents clear project-ttl",
                    delete("/api/cache/projects/agents/settings/project-ttl")),
            action("projects delete <project> [--yes]", "projects delete agents --yes",
                    delete("/api/cache/projects/agents")),
            action("scans [--cursor C]", "scans --cursor c", get("/api/scans", "after")),
            action("scans show <id>", "scans show s1", get("/api/scans/s1")),
            action("scans report <id>", "scans report s1", get("/api/scans/s1/report")),
            action("scans ingest <file>", "scans ingest @FILE", post("/api/scans").file()),
            action("scans analytics", "scans analytics", get("/api/scans/analytics")),
            action("scans analytics report", "scans analytics report", get("/api/scans/analytics/report")),
            action("tests ingest <file>", "tests ingest @FILE", post("/api/tests").file()),
            action("tests show <id>", "tests show t1", get("/api/tests/t1")),
            action("tests flaky [--limit N]", "tests flaky --limit 9", get("/api/tests/flaky", "limit")),
            action("tests select [--changed F]", "tests select --changed src/A.java",
                    get("/api/tests/select", "changed")),

            // Access
            action("credentials", "credentials", get("/api/credentials")),
            action("credentials mint [--label L]", "credentials mint --label ci",
                    post("/api/credentials").body("label")),
            action("credentials revoke <id>", "credentials revoke c1", delete("/api/credentials/c1")),
            action("credentials grant <id> <scope> <tokens>", "credentials grant c1 releases read",
                    post("/api/credentials/c1/grants").body("scope", "tokens")),
            action("credentials revoke-grant <id> <scope>", "credentials revoke-grant c1 releases",
                    delete("/api/credentials/c1/grants/releases")),
            action("credentials expiry <id> [<expiry>]", "credentials expiry c1 P30D",
                    put("/api/credentials/c1/expiry").body("expires")),
            action("credentials rotate <id> [<overlap>]", "credentials rotate c1 P1D",
                    post("/api/credentials/c1/rotate").body("overlap")),
            action("credentials allow-ips <id> [<cidrs>]", "credentials allow-ips c1 10.0.0.0/8",
                    put("/api/credentials/c1/allowed-ips").body("addresses")),
            action("members", "members", get("/api/principals")),
            action("members grant <id> <scope> <tokens> [<expiry>]", "members grant github/alice releases read",
                    post("/api/principals/grants").body("id", "scope", "tokens", "expires")),
            action("members revoke-grant <id> <scope>", "members revoke-grant github/alice releases",
                    delete("/api/principals/grants", "id", "scope")),
            action("members remove <id>", "members remove github/alice", delete("/api/principals", "id")),
            action("groups", "groups", get("/api/groups")),
            action("groups members <name>", "groups members devs", get("/api/groups/devs/members")),
            action("groups grant <name> <scope> <tokens> [<expiry>]", "groups grant devs releases read",
                    post("/api/groups/devs/grants").body("scope", "tokens", "expires")),
            action("groups revoke-grant <name> <scope>", "groups revoke-grant devs releases",
                    delete("/api/groups/devs/grants/releases")),
            action("groups add <name> <id>", "groups add devs github/alice",
                    post("/api/groups/devs/members").body("id")),
            action("groups remove-member <name> <id>", "groups remove-member devs github/alice",
                    delete("/api/groups/devs/members", "id")),
            action("groups remove <name>", "groups remove devs", delete("/api/groups/devs")),
            action("roles", "roles", get("/api/roles")),
            action("roles set <name> <tokens>", "roles set ci read", put("/api/roles/ci").body("tokens")),
            action("roles remove <name>", "roles remove ci", delete("/api/roles/ci")),
            action("trusts", "trusts", get("/api/trusts")),
            action("trusts set <name> --issuer I --scope S --rights R", "trusts set gh --issuer i --scope s --rights r",
                    put("/api/trusts/gh").body("issuer", "scope", "rights")),
            action("trusts remove <name>", "trusts remove gh", delete("/api/trusts/gh")),
            action("policy", "policy", get("/api/policy")),
            action("policy set [--default D] [--max D]", "policy set --default P30D --max P90D",
                    put("/api/policy").body("defaultLifetime", "maxLifetime")),
            action("audit [--from I] [--to I] [--action A] [--csv] [--cursor C]",
                    "audit --from a --to b --action c --cursor d", get("/api/audit", "from", "to", "action", "after")),
            action("scim token", "scim token", post("/api/scim/token")),
            action("scim token clear", "scim token clear", post("/api/scim/token/clear")),
            action("keylogin list", "keylogin list", get("/api/keylogin")),
            action("keylogin issue <principal> --tenant <name> [--login <display>] [--role <role>]",
                    "keylogin issue alice --tenant acme --login Alice --role admin",
                    post("/api/keylogin").body("principal", "tenant", "login", "role")),
            action("keylogin revoke <id>", "keylogin revoke k1", post("/api/keylogin/k1/delete")),

            // Operations
            action("metrics", "metrics", get("/api/admin/observability")),
            action("posture [--tenant N]", "posture --tenant acme", get("/api/admin/posture", "tenant")),
            action("caches", "caches", get("/api/admin/caches")),
            action("caches clear", "caches clear", post("/api/admin/caches/clear")),
            action("caches flush", "caches flush", post("/api/admin/caches/flush")),
            action("walks", "walks", get("/api/admin/walks")),
            action("walks run", "walks run", post("/api/admin/walks/run")),
            action("consistency", "consistency", get("/api/admin/consistency")),
            action("logs [--level L] [--limit N]", "logs --level WARN --limit 5",
                    get("/api/admin/logs", "level", "limit")),
            action("redirect-dns record <coordinate> <url> [--formats F] [--scope S] [--ttl N]",
                    "redirect-dns record a:b https://x/ --formats maven --scope exact --ttl 60",
                    get("/api/admin/redirect-dns/record", "coordinate", "url", "formats", "scope", "ttl")),
            action("redirect-dns check <coordinate> [--expect U]", "redirect-dns check a:b --expect https://x/",
                    get("/api/admin/redirect-dns/check", "coordinate", "expect")),

            // Settings
            action("settings [--tenant N]", "settings --tenant acme", get("/api/settings", "tenant")),
            action("settings set <key> <value> [--tenant N]", "settings set vulnerability-threshold HIGH",
                    put("/api/settings/vulnerability-threshold").body("value")),
            action("settings clear <key> [--tenant N]", "settings clear vulnerability-threshold --tenant acme",
                    delete("/api/settings/vulnerability-threshold", "tenant")),
            action("settings export [file] [--tenant N]", "settings export", get("/api/settings/export")),
            action("settings import <file> [--tenant N]", "settings import @FILE",
                    post("/api/settings/import").file()),
            action("setup", "setup", get("/api/setup")),
            action("setup set <key> <value>", "setup set walks on", put("/api/settings/walks").body("value")),
            action("tenants", "tenants", get("/api/admin/tenants")),
            action("tenants create <name>", "tenants create acme", put("/api/admin/tenants/acme")),
            action("tenants delete <name> [--yes]", "tenants delete acme --yes", delete("/api/admin/tenants/acme")),
            action("repos", "repos", get("/api/repositories")),
            action("repos create <name> <format> [description] [--set <key>=<value>]...",
                    "repos create libs maven Build outputs --set keep-last=3",
                    put("/repository/releases/libs").body("value", "description", "settings")),
            action("repos describe <name> <description>", "repos describe libs the libraries",
                    put("/repository/releases/libs").body("description")),
            action("repos settings <name>", "repos settings libs", get("/api/repository/settings", "repo")),
            action("repos settings <name> set <key> <value>", "repos settings libs set keep-last 3",
                    put("/api/repository/settings/keep-last", "repo").body("value")),
            action("repos settings <name> clear <key>", "repos settings libs clear keep-last",
                    delete("/api/repository/settings/keep-last", "repo")),
            action("repos delete <name> [--yes]", "repos delete libs --yes", delete("/repository/releases/libs")),
            action("repos set <name> <definition>", "repos set mirror writable",
                    put("/api/repositories/mirror").body("value")),
            action("repos remove <name>", "repos remove mirror", delete("/api/repositories/mirror")),
            action("upstreams [--tenant N]", "upstreams", get("/api/upstreams")),
            action("upstreams set <format> <url> [--tenant N]", "upstreams set maven https://x/",
                    put("/api/upstreams/maven").body("value")),
            action("upstreams remove <format> [--tenant N]", "upstreams remove maven",
                    delete("/api/upstreams/maven")),
            action("upstreams auth", "upstreams auth", get("/api/upstreams/auth")),
            action("upstreams auth set <host> <bearer|basic|header|aws> ...", "upstreams auth set x.io bearer t",
                    put("/api/upstreams/auth/x.io").body("scheme", "token")),
            action("upstreams auth remove <host>", "upstreams auth remove x.io",
                    delete("/api/upstreams/auth/x.io")),
            action("limits", "limits", get("/api/quota"), get("/api/rate-limit")),
            action("limits set quota <bytes> [--tenant N]", "limits set quota 1024 --tenant acme",
                    put("/api/quota", "tenant").body("maxBytes")),
            action("limits set rate <permits-per-minute> [--tenant N]", "limits set rate 60 --tenant acme",
                    put("/api/rate-limit", "tenant").body("permitsPerMinute")),
            action("capabilities", "capabilities", get("/api/capabilities")),
            action("spi", "spi", get("/api/admin/spi")),
            action("config", "config", get("/api/config")),
            action("purge", "purge", get("/api/admin/orphans")),
            action("purge <module> [--delete]", "purge build.jenesis.gone",
                    post("/api/admin/purge", "namespace", "dryRun")));

    @TempDir
    private static Path home;

    private static WireMockServer server;

    private static Path payload;

    /** What the file an action forwards holds - told apart from a document the client composed by its content. */
    private static final String PAYLOAD = "{\"document\":true}";

    @BeforeAll
    public void setUp() throws Exception {
        server = new WireMockServer(WireMockConfiguration.options().bindAddress("127.0.0.1").dynamicPort());
        server.start();
        server.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{}")));
        payload = home.resolve("payload.json");
        Files.writeString(payload, PAYLOAD);
        System.setProperty("JENREPO_CLI_HOME", home.toString());
        Cli.run(new String[] {"login", "http://127.0.0.1:" + server.port() + "/", "--key-file",
                Files.writeString(home.resolve("key"), "test-key").toString()});
    }

    @AfterAll
    public void tearDown() {
        System.clearProperty("JENREPO_CLI_HOME");
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void every_action_is_covered_by_this_test() {
        Set<String> declared = new TreeSet<>();
        for (Commands.Noun noun : Commands.BY_NAME.values()) {
            if (!LOCAL.contains(noun.name())) {
                noun.actions().forEach(action -> declared.add(action.form()));
            }
        }
        assertThat(new TreeSet<>(ACTIONS.keySet()))
                .as("every action form must state the request it sends - an action with no case is a request "
                        + "nobody has held to what its endpoint binds")
                .isEqualTo(declared);
    }

    @TestFactory
    Stream<DynamicTest> every_action_sends_what_its_endpoint_binds() {
        return ACTIONS.entrySet().stream().map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
            Case action = entry.getValue();
            String[] args = Arrays.stream(action.line().split(" "))
                    .map(arg -> arg.equals("@FILE") ? payload.toString() : arg)
                    .toArray(String[]::new);
            server.resetRequests();
            try {
                Cli.run(args);
            } catch (Exception failedAfterTheCall) {
                // Expected for anything whose stub reply it cannot parse; the requests are what is under test.
            }
            List<String> sent = server.findAll(RequestPatternBuilder.allRequests()).stream()
                    .map(CliRequestShapeTest::shape).toList();
            assertThat(sent).as("'%s' sends what its endpoint binds", action.line())
                    .containsExactlyElementsOf(action.requests().stream().map(Sent::toString).toList());
        }));
    }

    /** What arrived, in the terms a case states it: method, path, query parameter names and the body's shape. */
    private static String shape(LoggedRequest request) {
        URI uri = URI.create(request.getUrl());
        Set<String> query = new TreeSet<>();
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                query.add(URLDecoder.decode(pair.split("=", 2)[0], StandardCharsets.UTF_8));
            }
        }
        String body = request.getBodyAsString();
        Set<String> json = null;
        boolean raw = false;
        if (body != null && !body.isEmpty()) {
            if (body.equals(PAYLOAD)) {
                raw = true;
            } else {
                json = new TreeSet<>();
                JSON.readTree(body).propertyNames().forEach(json::add);
            }
        }
        return new Sent(request.getMethod().toString(), uri.getRawPath(), query, json, raw).toString();
    }
}
