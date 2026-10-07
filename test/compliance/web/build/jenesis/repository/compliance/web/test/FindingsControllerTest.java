package build.jenesis.repository.compliance.web.test;

import module java.base;
import module org.junit.jupiter.api;
import module tools.jackson.databind;
import org.junit.jupiter.api.io.TempDir;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.compliance.VulnerabilityPolicy;
import build.jenesis.repository.compliance.VulnerabilityRecord;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.web.FindingsController;
import build.jenesis.repository.findings.Finding;
import build.jenesis.repository.findings.store.StoreFindingsProvider;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.servlet.testkit.Servlets;
import build.jenesis.repository.web.testkit.Web;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The findings endpoint pages by an opaque cursor, as every paged answer of the API does: a page hands out
 * {@code next} while more remain, the caller passes it back as {@code after}, and the last page carries none - so a
 * walk of the pages yields every finding exactly once. A cursor the endpoint did not hand out is refused rather than
 * read as the first page. A report saying how much of a version it read in words the endpoint does not know is refused
 * before anything is decided, and a reported finding stating its source as CycloneDX's {@code vulnerability} object is
 * recorded with that structure.
 */
class FindingsControllerTest {

    private static final Instant SEEN = Instant.parse("2026-10-05T00:00:00Z");

    @TempDir
    Path root;

    private Repositories repositories;
    private FindingsController controller;

    @BeforeEach
    void setUp() throws IOException {
        repositories = Web.repositories(root);
        StoreFindingsProvider findings = new StoreFindingsProvider();
        var ledger = findings.over(repositories.store(Scopes.DEFAULT_TENANT, "releases"));
        for (int index = 0; index < 5; index++) {
            ledger.record("npm", "lib" + index, "1.0.0", Finding.of("GHSA-" + index, "osv",
                    Finding.Kind.VULNERABILITY, "", Severity.HIGH, "advised", SEEN));
        }
        controller = new FindingsController(repositories, Web.routing(repositories, Scopes.DEFAULT_TENANT),
                AuditTrail.none(), Optional.of(findings), _ -> {
                    throw new AssertionError("no report is decided here");
                });
    }

    @Test
    void the_pages_are_walked_by_the_cursor_each_hands_out_until_one_hands_out_none() throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        String after = null;
        int pages = 0;
        do {
            Servlets.Response response = Servlets.response();
            FindingsController.FindingsView page = page(after, response);
            assertThat(response.status()).isEqualTo(200);
            page.findings().forEach(finding -> assertThat(ids.add(finding.id()))
                    .as("%s is answered once across the pages", finding.id()).isTrue());
            after = page.next();
            pages++;
        } while (after != null && pages < 10);

        assertThat(ids).as("every finding, across three pages of two").hasSize(5);
        assertThat(pages).isEqualTo(3);
    }

    @Test
    void a_cursor_the_endpoint_did_not_hand_out_is_refused() throws IOException {
        for (String forged : List.of("not-a-cursor", "-1")) {
            Servlets.Response response = Servlets.response();

            assertThat(page(forged, response)).isNull();
            assertThat(response.status()).as(forged).isEqualTo(400);
        }
    }

    @Test
    void a_report_whose_coverage_does_not_read_is_refused_before_anything_is_decided() throws IOException {
        List<String> gap = List.of("no package manager known");
        for (FindingsController.ReportRequest request : List.of(
                report("most", gap), report(null, gap), report("partial", Collections.nCopies(101, "a gap")))) {
            Servlets.Response response = Servlets.response();

            assertThat(controller.report("releases", null, request, Servlets.request("POST", "/api/findings/report"),
                    response.servlet())).isNull();
            assertThat(response.status()).as(request.completeness() + " " + request.gaps().size()).isEqualTo(400);
        }
    }

    @Test
    void a_reported_findings_cyclonedx_vulnerability_is_recorded_with_it() throws IOException {
        String path = "/maven/org/example/app/1.0/app-1.0.jar";
        ArtifactStore releases = repositories.writable(Scopes.DEFAULT_TENANT, "releases");
        Publication publication = new Publication(releases);
        publication.link(path, publication.storeBlob(new ByteArrayInputStream(new byte[]{1})));
        new StoreRepositoryInventory(releases).record(path, SEEN);
        ComplianceGate gate = new ComplianceGate(new VulnerabilityPolicy(Severity.CRITICAL, Verdict.REJECT),
                AdvisorySource.none());
        StoreFindingsProvider findings = new StoreFindingsProvider();
        FindingsController reporting = new FindingsController(repositories,
                Web.routing(repositories, Scopes.DEFAULT_TENANT), AuditTrail.none(), Optional.of(findings),
                _ -> gate);
        JsonNode vulnerability = JsonMapper.builder().build().readTree("""
                {"id": "CVE-2026-1",
                 "source": {"name": "NVD", "url": "https://nvd.nist.gov/vuln/detail/CVE-2026-1"},
                 "ratings": [{"source": {"name": "NVD"}, "score": 5.3, "severity": "medium", "method": "CVSSv31",
                              "vector": "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N"}],
                 "cwes": [79]}""");
        Servlets.Response response = Servlets.response();

        reporting.report("releases", null, new FindingsController.ReportRequest("scanner", "Maven",
                "org.example:app", "1.0", List.of(new FindingsController.Reported("CVE-2026-1", "MEDIUM", List.of(),
                null, "reflected", false, vulnerability)), null, null),
                Servlets.request("POST", "/api/findings/report"), response.servlet());

        assertThat(response.status()).isEqualTo(200);
        assertThat(findings.over(repositories.store(Scopes.DEFAULT_TENANT, "releases"))
                .of("Maven", "org.example:app", "1.0")).singleElement().satisfies(finding -> {
                    assertThat(finding.detail().source()).isEqualTo(new VulnerabilityRecord.Source("NVD",
                            "https://nvd.nist.gov/vuln/detail/CVE-2026-1"));
                    assertThat(finding.detail().ratings()).singleElement().satisfies(rating ->
                            assertThat(rating.vector()).isEqualTo("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N"));
                    assertThat(finding.detail().cwes()).containsExactly(79);
                });

        FindingsController.FindingsView listed = reporting.findings("releases", "org.example:app", null, null, null,
                null, null, null, 10, Servlets.request("GET", "/api/findings"), Servlets.response().servlet());
        assertThat(listed.findings()).singleElement().satisfies(row -> {
            assertThat(row.vulnerability().path("source").path("name").asString()).isEqualTo("NVD");
            assertThat(row.vulnerability().path("ratings").get(0).path("vector").asString())
                    .isEqualTo("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N");
            assertThat(row.vulnerability().path("description").asString()).isEqualTo("reflected");
        });

        Servlets.Response exported = Servlets.response();
        reporting.cyclonedx("releases", "Maven", "org.example:app", "1.0", Servlets.request("GET",
                "/api/findings/cyclonedx"), exported.servlet());
        assertThat(exported.status()).isEqualTo(200);
        assertThat(exported.contentType()).startsWith("application/vnd.cyclonedx+json");
        JsonNode bom = JsonMapper.builder().build().readTree(exported.body());
        assertThat(bom.path("bomFormat").asString()).isEqualTo("CycloneDX");
        String purl = "pkg:maven/org.example/app@1.0";
        assertThat(bom.path("metadata").path("component").path("purl").asString()).isEqualTo(purl);
        assertThat(bom.path("vulnerabilities")).singleElement().satisfies(entry -> {
            assertThat(entry.path("id").asString()).isEqualTo("CVE-2026-1");
            assertThat(entry.path("affects").get(0).path("ref").asString()).isEqualTo(purl);
            assertThat(VulnerabilityRecord.fromCycloneDx(entry).cwes()).as("read back as what was recorded")
                    .containsExactly(79);
            assertThat(entry.path("properties").get(0).path("value").asString()).isEqualTo("scanner");
        });
    }

    @Test
    void a_version_with_no_findings_exports_an_empty_document_and_a_blank_name_is_refused() throws IOException {
        Servlets.Response exported = Servlets.response();
        controller.cyclonedx("releases", "Maven", "org.example:none", "1.0", Servlets.request("GET",
                "/api/findings/cyclonedx"), exported.servlet());
        assertThat(exported.status()).isEqualTo(200);
        assertThat(JsonMapper.builder().build().readTree(exported.body()).path("vulnerabilities")).isEmpty();

        Servlets.Response refused = Servlets.response();
        controller.cyclonedx("releases", "Maven", " ", "1.0", Servlets.request("GET", "/api/findings/cyclonedx"),
                refused.servlet());
        assertThat(refused.status()).isEqualTo(400);
    }

    private static FindingsController.ReportRequest report(String completeness, List<String> gaps) {
        return new FindingsController.ReportRequest("scanner", "npm", "lib0", "1.0.0", List.of(), completeness,
                gaps);
    }

    private FindingsController.FindingsView page(String after, Servlets.Response response) throws IOException {
        return controller.findings("releases", null, null, null, null, null, null, after, 2,
                Servlets.request("GET", "/api/findings"), response.servlet());
    }
}
