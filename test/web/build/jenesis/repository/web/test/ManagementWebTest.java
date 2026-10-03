package build.jenesis.repository.web.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.store.HeldWrites;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.management.web.CachesAdminController;
import build.jenesis.repository.management.web.ManagementController;
import build.jenesis.repository.management.web.PostureAdminController;
import build.jenesis.repository.management.web.SpiCatalogController;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.servlet.testkit.Servlets;
import build.jenesis.repository.web.testkit.Web;
import org.springframework.core.env.ConfigurableEnvironment;
import jakarta.servlet.http.HttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The management API's handlers over a real store and an enforcing authorization: the tenant's credential policy,
 * storage quota, request-rate ceiling and named roles read back what was written and each write is audited; the audit
 * trail pages by cursor and exports as CSV a spreadsheet cannot evaluate; the posture report is collected over the
 * effective chain and scoped to one named tenant or none; the SPI catalogue lists what the module path carries; and
 * clearing the caches is recorded in the operator scope.
 */
class ManagementWebTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private Web.Recording audit;
    private ManagementController controller;

    @BeforeEach
    void wire() throws IOException {
        store = Web.store(root);
        Authorization authorization = Authorization.enforcing(store);
        Repositories repositories = Web.repositories(store, authorization);
        audit = Web.audit();
        controller = new ManagementController(repositories, Web.routing(store, repositories), authorization, audit,
                repositories.live(), Web.editor(repositories, audit));
    }

    /** An {@code /api} call, which names no tenant: the routing answers the one this deployment serves. */
    private static HttpServletRequest request() {
        return Servlets.request("PUT", "/api/policy");
    }

    @Test
    void a_lifetime_policy_reads_back_as_written_and_is_audited() throws IOException {
        Servlets.Response response = Servlets.response();

        controller.setPolicy(null, new ManagementController.PolicyRequest("P30D", "P90D"), request(),
                response.servlet());

        assertThat(response.status()).isEqualTo(200);
        assertThat(controller.policy(request())).isEqualTo(new ManagementController.PolicyView("PT720H", "PT2160H"));
        assertThat(audit.actions()).containsExactly(AuditActions.POLICY_SET);
    }

    @Test
    void a_lifetime_that_is_not_a_duration_is_a_bad_request() throws IOException {
        assertThatThrownBy(() -> controller.setPolicy(null, new ManagementController.PolicyRequest("a month", null),
                request(), Servlets.response().servlet())).isInstanceOf(IllegalArgumentException.class);
        Servlets.Response mapped = Servlets.response();
        controller.badRequest(mapped.servlet());

        assertThat(mapped.status()).isEqualTo(400);
        assertThat(audit.rows()).isEmpty();
    }

    @Test
    void a_quota_is_the_ceiling_set_beside_what_is_stored() throws IOException {
        controller.setQuota(null, new ManagementController.QuotaRequest(1_048_576), request(),
                Servlets.response().servlet());

        assertThat(controller.quota(request())).isEqualTo(new ManagementController.QuotaView(1_048_576, 0));
        assertThat(audit.rows()).as("the tenant's quota setting changed, recorded as every surface records it")
                .singleElement().satisfies(row -> {
                    assertThat(row.action()).isEqualTo(AuditActions.SETTING_SET);
                    assertThat(row.target()).isEqualTo("default/tenant-quota");
                });

        controller.setQuota(null, new ManagementController.QuotaRequest(0), request(), Servlets.response().servlet());
        assertThat(controller.quota(request()).maxBytes()).as("zero clears the ceiling").isZero();
    }

    @Test
    void a_rate_ceiling_reads_back_as_written() throws IOException {
        controller.setRateLimit(null, new ManagementController.RateLimitRequest(600), request(),
                Servlets.response().servlet());

        assertThat(controller.rateLimit(request(), Servlets.response().servlet()).permitsPerMinute()).isEqualTo(600);
        assertThat(audit.rows()).singleElement().satisfies(row -> {
            assertThat(row.action()).isEqualTo(AuditActions.SETTING_SET);
            assertThat(row.target()).isEqualTo("default/rate-limit");
        });
    }

    @Test
    void a_custom_role_joins_the_built_in_ones_until_it_is_removed() throws IOException {
        assertThat(controller.roles(request())).containsKeys("read-only", "deploy", "admin");

        controller.setRole("ci", null, new ManagementController.RoleRequest("repository:read,repository:write"),
                request(), Servlets.response().servlet());
        assertThat(controller.roles(request())).containsEntry("ci", "repository:read,repository:write");

        controller.removeRole("ci", null, request(), Servlets.response().servlet());
        assertThat(controller.roles(request())).doesNotContainKey("ci");
        assertThat(audit.actions()).containsExactly(AuditActions.ROLE_SET, AuditActions.ROLE_REMOVE);
    }

    @Test
    void the_audit_trail_pages_by_cursor_and_says_when_it_is_done() throws IOException {
        for (String role : List.of("one", "two", "three")) {
            controller.setRole(role, null, new ManagementController.RoleRequest("repository:read"),
                    request(), Servlets.response().servlet());
        }
        Servlets.Response first = Servlets.response();

        List<ManagementController.AuditView> page = controller.auditTrail(null, null, null, 0, null, 2, request(),
                first.servlet());

        assertThat(page).hasSize(2);
        String next = first.header("Jenesis-Next-Cursor");
        assertThat(next).as("more remains, so the answer says where it resumes").isNotNull();
        Servlets.Response last = Servlets.response();
        List<ManagementController.AuditView> rest = controller.auditTrail(null, null, null, 0, next, 2, request(),
                last.servlet());
        assertThat(rest).hasSize(1);
        assertThat(last.header("Jenesis-Next-Cursor")).as("the last page carries no cursor").isNull();
        assertThat(controller.auditTrail(null, null, AuditActions.ROLE_SET, 1, null, 500, request(),
                Servlets.response().servlet())).as("an offset page").hasSize(2);
    }

    @Test
    void the_csv_export_quotes_what_needs_it_and_defuses_a_formula() throws IOException {
        controller.setRole("=HYPERLINK(\"x\")", null, new ManagementController.RoleRequest("repository:read"),
                request(), Servlets.response().servlet());
        controller.setRole("plain,with comma", null, new ManagementController.RoleRequest("repository:read"),
                request(), Servlets.response().servlet());
        Servlets.Response response = Servlets.response();

        controller.auditCsv(null, null, null, request(), response.servlet());

        assertThat(response.contentType()).isEqualTo("text/csv;charset=UTF-8");
        List<String> lines = response.body().lines().toList();
        assertThat(lines).first().isEqualTo("at,actor,action,target");
        assertThat(lines).anyMatch(line -> line.endsWith(",\"'=HYPERLINK(\"\"x\"\")\""));
        assertThat(lines).anyMatch(line -> line.endsWith(",\"plain,with comma\""));
    }

    @Test
    void with_no_audit_module_the_trail_answers_501() throws IOException {
        Repositories repositories = Web.repositories(store);
        ManagementController unaudited = new ManagementController(repositories, Web.routing(store, repositories),
                Authorization.enforcing(store), AuditTrail.none(), repositories.live(),
                Web.editor(repositories, AuditTrail.none()));
        Servlets.Response page = Servlets.response();
        Servlets.Response csv = Servlets.response();

        assertThat(unaudited.auditTrail(null, null, null, 0, null, 10, request(), page.servlet())).isNull();
        unaudited.auditCsv(null, null, null, request(), csv.servlet());

        assertThat(page.status()).isEqualTo(501);
        assertThat(csv.status()).isEqualTo(501);
        assertThat(csv.body()).isEqualTo("audit is not installed on this deployment");
    }

    @Test
    void the_posture_report_reads_the_effective_chain_and_tallies_what_it_emits() throws IOException {
        ConfigurableEnvironment open = Web.environment(Map.of("jenrepo.auth", "false"));
        PostureAdminController posture = new PostureAdminController(new Settings(store), open, Web.pins(open));

        PostureAdminController.PostureView deployment = posture.posture(null);

        assertThat(deployment.version()).isEqualTo(1);
        assertThat(deployment.tenant()).isEmpty();
        assertThat(deployment.advisories()).anySatisfy(row -> {
            assertThat(row.id()).isEqualTo("jenrepo.auth.open");
            assertThat(row.severity()).isEqualTo("CRITICAL");
            assertThat(row.settingKey()).isEqualTo("jenrepo.auth");
        });
        assertThat(deployment.count()).isEqualTo(deployment.advisories().size());
        assertThat(deployment.critical() + deployment.warn() + deployment.info()).isEqualTo(deployment.count());
        assertThat(posture.posture(" acme ").tenant()).as("a named read echoes its tenant").isEqualTo("acme");
    }

    @Test
    void a_malformed_tenant_is_refused_rather_than_answered_deployment_wide() throws IOException {
        ConfigurableEnvironment environment = Web.environment(Map.of());
        PostureAdminController posture = new PostureAdminController(new Settings(store), environment,
                Web.pins(environment));

        IllegalArgumentException refused = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> posture.posture("../acme"));
        Servlets.Response response = Servlets.response();
        posture.badRequest(refused, response.servlet());

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).startsWith("'../acme' is not a tenant name.").contains("no all-tenants form");
    }

    @Test
    void the_spi_catalogue_lists_the_store_backend_this_module_path_carries() throws IOException {
        ConfigurableEnvironment environment = Web.environment(Map.of());
        SpiCatalogController catalogue = new SpiCatalogController(new Settings(store), environment,
                Web.pins(environment));

        SpiCatalogController.CatalogView view = catalogue.spi();

        assertThat(view.version()).isEqualTo(1);
        assertThat(view.spis()).filteredOn(spi -> spi.name().equals("ArtifactStoreProvider")).singleElement()
                .satisfies(spi -> assertThat(spi.implementations())
                        .anySatisfy(implementation -> {
                            assertThat(implementation.name()).isEqualTo("FilesystemArtifactStoreProvider");
                            assertThat(implementation.installed()).isTrue();
                        }));
    }

    @Test
    void clearing_the_caches_answers_what_went_and_is_recorded_in_the_routed_tenants_trail() throws IOException {
        // Operated by another tenant than the one it serves: the call answers for the served one, and records there.
        CachesAdminController caches = new CachesAdminController(audit, Authorization.enforcing(store),
                Web.routing(Web.repositories(store), "acme", "ops"));

        CachesAdminController.CachesView before = caches.caches();
        CachesAdminController.ClearedView cleared = caches.clear("operator-key",
                Servlets.request("POST", "/api/admin/caches/clear"));

        assertThat(cleared.node()).isEqualTo(before.node());
        assertThat(cleared.cleared()).isNotNegative();
        assertThat(audit.rows()).singleElement().satisfies(row -> {
            assertThat(row.action()).isEqualTo(AuditActions.CACHES_CLEAR);
            assertThat(row.tenant()).isEqualTo("acme");
            assertThat(row.actor()).isEqualTo(Authorization.hash("operator-key"));
        });
    }

    @Test
    void a_flush_asks_every_held_write_to_land_answers_what_each_held_and_is_recorded() throws IOException {
        AtomicInteger asked = new AtomicInteger();
        HeldWrites.Holder holder = new HeldWrites.Holder() {
            @Override
            public String what() {
                return "test counts";
            }

            @Override
            public String cadence() {
                return "every PT6H";
            }

            @Override
            public long pending() {
                return asked.get() == 0 ? 4 : 0;
            }

            @Override
            public void writeNow() {
                asked.incrementAndGet();
            }
        };
        HeldWrites.hold(holder);
        try {
            CachesAdminController caches = new CachesAdminController(audit, Authorization.enforcing(store),
                    Web.routing(Web.repositories(store), "acme", "ops"));

            assertThat(caches.caches().held()).as("what the node holds is listed beside its caches")
                    .contains(new HeldWrites.Held("test counts", "every PT6H", 4));
            CachesAdminController.FlushedView flushed = caches.flush("operator-key",
                    Servlets.request("POST", "/api/admin/caches/flush"));

            assertThat(asked).as("the holder was asked to write").hasValue(1);
            assertThat(flushed.asked()).as("the answer says what was held when asked")
                    .contains(new HeldWrites.Held("test counts", "every PT6H", 4));
            assertThat(audit.rows()).singleElement().satisfies(row -> {
                assertThat(row.action()).isEqualTo(AuditActions.CACHES_FLUSH);
                assertThat(row.tenant()).isEqualTo("acme");
                assertThat(row.actor()).isEqualTo(Authorization.hash("operator-key"));
            });
        } finally {
            HeldWrites.release(holder);
        }
    }
}
