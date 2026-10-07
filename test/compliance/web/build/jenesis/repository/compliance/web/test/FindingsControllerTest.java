package build.jenesis.repository.compliance.web.test;

import module java.base;
import module org.junit.jupiter.api;
import org.junit.jupiter.api.io.TempDir;
import build.jenesis.repository.audit.AuditTrail;
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
 * read as the first page.
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

    private FindingsController.FindingsView page(String after, Servlets.Response response) throws IOException {
        return controller.findings("releases", null, null, null, null, null, null, after, 2,
                Servlets.request("GET", "/api/findings"), response.servlet());
    }
}
