package build.jenesis.repository.web.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.cleanup.StoredReport;
import build.jenesis.repository.cleanup.web.MaintenanceController;
import build.jenesis.repository.cleanup.web.RepositoryCleanup;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.servlet.testkit.Servlets;
import build.jenesis.repository.web.testkit.Web;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The repository-maintenance surface over a real store: the pin markers, the retention policy, the cleanup sweep and
 * its dry run, and the refusals around them.
 *
 * <p>A pin is what stops retention from evicting a version, so it is a small write with a large consequence: an
 * unpin that silently does nothing leaves an operator believing a version is protected when the next sweep will
 * take it, and a pin whose coordinate was not validated writes a marker at a key nobody will ever look at - which
 * fails in exactly the same, invisible way. Both directions are driven here, so "pinned" means the marker is there
 * and "unpinned" means it is gone. The ecosystem, coordinate and version all become path segments, so an unsafe one
 * is a 400 rather than an exception out of the handler.
 *
 * <p>The sweep runs over releases recorded in the repository's inventory under a stored {@code keepLast} policy, with
 * the retention module and the Maven format on the path and no collector selected - so the report names what it
 * evicted and says that garbage collection is off, which is the answer a deployment with no collector gets. Releases
 * recorded under an ecosystem no installed format places are refused by the sweep and are what the explicit
 * retirement forgets.
 */
class MaintenanceControllerTest {

    private static final String REPO = "releases";
    /** The ecosystem name the Maven format declares - the vulnerability databases' spelling. */
    private static final String ECOSYSTEM = "Maven";
    private static final String COORDINATE = "com.acme:widget";

    /** An ecosystem no format on this module path declares - what a removed format module leaves behind. */
    private static final String UNPLACED = "cobol";

    @TempDir
    Path root;

    private Repositories repositories;
    private Web.Recording audit;
    private MaintenanceController controller;

    @BeforeEach
    void wire() throws IOException {
        ArtifactStore store = Web.store(root);
        repositories = Web.repositories(store);
        audit = Web.audit();
        controller = new MaintenanceController(repositories, Web.routing(store, repositories), repositories.live(),
                Web.editor(repositories, audit), ObservationRegistry.NOOP, audit, Web.scheduler(repositories, store));
    }

    /** The sweep - or, with {@code plan}, the dry run - once its run off the request has finished. */
    private RepositoryCleanup.View settled(boolean plan) throws IOException {
        StoredReport.awaitSettled(repositories.store("default", REPO),
                plan ? RepositoryCleanup.PLAN : RepositoryCleanup.RUN, Duration.ofSeconds(30)).orElseThrow();
        return plan ? controller.cleanupPlan(REPO, false, request(), Servlets.response().servlet())
                : controller.lastCleanup(REPO, request(), Servlets.response().servlet());
    }

    private StoreRepositoryInventory inventory() {
        return new StoreRepositoryInventory(repositories.store("default", REPO));
    }

    /** Three versions of one coordinate, a day apart, oldest first. */
    private void recordThreeVersions() throws IOException {
        recordThreeVersions(ECOSYSTEM);
    }

    private void recordThreeVersions(String ecosystem) throws IOException {
        Instant first = Instant.parse("2026-01-01T00:00:00Z");
        for (int index = 0; index < 3; index++) {
            inventory().record(ecosystem, COORDINATE, "1." + index + ".0", first.plus(Duration.ofDays(index)));
        }
    }

    @Test
    void a_pinned_version_is_marked_and_an_unpinned_one_is_not() throws Exception {
        controller.pin(REPO, ECOSYSTEM, COORDINATE, "1.0.0", null, request(), Servlets.response().servlet());

        assertThat(inventory().pinned()).as("retention must be able to see the marker")
                .containsExactly(new StoreRepositoryInventory.Pin(ECOSYSTEM, COORDINATE, "1.0.0"));
        assertThat(controller.pins(REPO, null, request(), Servlets.response().servlet()).pinned()).hasSize(1);

        controller.unpin(REPO, ECOSYSTEM, COORDINATE, "1.0.0", null, request(), Servlets.response().servlet());

        // The direction that fails silently: an unpin that did nothing leaves an operator believing a version is
        // still protected, and nothing says otherwise until the sweep takes it.
        assertThat(inventory().pinned()).isEmpty();
    }

    @Test
    void pinning_the_same_version_twice_is_not_an_error() throws Exception {
        controller.pin(REPO, ECOSYSTEM, COORDINATE, "1.0.0", null, request(), Servlets.response().servlet());
        Servlets.Response second = Servlets.response();

        controller.pin(REPO, ECOSYSTEM, COORDINATE, "1.0.0", null, request(), second.servlet());

        // An operator re-running a protection script must not get a failure for a state that is already correct.
        assertThat(second.status()).isLessThan(400);
        assertThat(inventory().pinned()).hasSize(1);
    }

    @Test
    void unpinning_something_that_was_never_pinned_is_not_an_error() throws Exception {
        Servlets.Response response = Servlets.response();

        controller.unpin(REPO, ECOSYSTEM, COORDINATE, "9.9.9", null, request(), response.servlet());

        assertThat(response.status()).isLessThan(400);
    }

    @Test
    void an_unsafe_segment_is_a_bad_request_and_writes_nothing() throws Exception {
        for (String unsafe : List.of("../etc", "a/b")) {
            Servlets.Response pin = Servlets.response();
            controller.pin(REPO, unsafe, COORDINATE, "1.0.0", null, request(), pin.servlet());
            Servlets.Response unpin = Servlets.response();
            controller.unpin(REPO, unsafe, COORDINATE, "1.0.0", null, request(), unpin.servlet());

            // 400, not a 500: the client is being told its request was wrong, not that the server broke.
            assertThat(pin.status()).as("ecosystem %s", unsafe).isEqualTo(400);
            assertThat(unpin.status()).as("ecosystem %s", unsafe).isEqualTo(400);
        }
        Servlets.Response version = Servlets.response();
        controller.pin(REPO, ECOSYSTEM, COORDINATE, "../../.system", null, request(), version.servlet());

        assertThat(version.status()).isEqualTo(400);
        assertThat(audit.rows()).isEmpty();
    }

    @Test
    void an_invalid_repository_name_is_refused_before_any_write() {
        assertThatThrownBy(() -> controller.pin("..", ECOSYSTEM, COORDINATE, "1.0.0", null, request(),
                Servlets.response().servlet()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        refused -> assertThat(refused.getStatusCode().value()).isEqualTo(400));
        assertThat(audit.rows()).isEmpty();
    }

    @Test
    void a_pin_toggle_is_audited_because_it_shapes_what_a_later_sweep_deletes() throws Exception {
        controller.pin(REPO, ECOSYSTEM, COORDINATE, "1.0.0", null, request(), Servlets.response().servlet());

        // The exact name, not a prefix: the trail is queried by action, so a second spelling for the same act is
        // a query that quietly comes back short.
        assertThat(audit.actions()).containsExactly(AuditActions.REPOSITORY_PIN);

        controller.unpin(REPO, ECOSYSTEM, COORDINATE, "1.0.0", null, request(), Servlets.response().servlet());

        assertThat(audit.actions()).containsExactly(AuditActions.REPOSITORY_PIN, AuditActions.REPOSITORY_UNPIN);
    }

    @Test
    void a_stored_retention_policy_reads_back_and_is_audited() throws Exception {
        Servlets.Response write = Servlets.response();

        controller.setRetention(REPO, "3", "P30D", "", "P90D", "key", request(), write.servlet());

        assertThat(write.status()).isEqualTo(200);
        MaintenanceController.RetentionView view = controller.retention(REPO, null, request(),
                Servlets.response().servlet());
        assertThat(view).isEqualTo(new MaintenanceController.RetentionView(3, "PT720H", "", "PT2160H"));
        assertThat(audit.rows()).as("each rule is the repository's setting, recorded as every surface records it")
                .extracting(Web.Recorded::action, Web.Recorded::target).containsExactly(
                        tuple(AuditActions.SETTING_SET, "default/" + REPO + "/keep-last"),
                        tuple(AuditActions.SETTING_SET, "default/" + REPO + "/max-age"),
                        tuple(AuditActions.SETTING_SET, "default/" + REPO + "/not-downloaded-for"),
                        tuple(AuditActions.SETTING_CLEAR, "default/" + REPO + "/prerelease-expiry"));
    }

    @Test
    void a_malformed_retention_dial_is_refused_and_never_stored() throws Exception {
        Servlets.Response write = Servlets.response();

        controller.setRetention(REPO, "3", "a month", "", "", null, request(), write.servlet());

        assertThat(write.status()).isEqualTo(400);
        assertThat(write.body()).isNotBlank();
        assertThat(controller.retention(REPO, null, request(), Servlets.response().servlet()))
                .as("nothing was stored: the repository runs under the deployment's empty policy")
                .isEqualTo(new MaintenanceController.RetentionView(0, "", "", ""));
        assertThat(audit.rows()).isEmpty();
    }

    @Test
    void the_dry_run_names_what_the_policy_would_evict_and_deletes_nothing() throws Exception {
        recordThreeVersions();
        controller.setRetention(REPO, "1", "", "", "", null, request(), Servlets.response().servlet());

        assertThat(controller.cleanupPlan(REPO, false, request(), Servlets.response().servlet()).state())
                .as("a read never runs the dry run, which judges every release").isEqualTo("not-run");
        Servlets.Response started = Servlets.response();
        controller.cleanupPlan(REPO, true, request(), started.servlet());
        assertThat(started.servlet().getHeader("Jenesis-Refresh")).isEqualTo("started");
        RepositoryCleanup.View plan = settled(true);

        assertThat(plan.state()).isEqualTo("done");
        assertThat(plan.evictedCount()).isEqualTo(2);
        assertThat(plan.evicted()).hasSize(2).allSatisfy(row -> assertThat(row).startsWith(COORDINATE + ":1."));
        assertThat(plan.blobsReclaimed()).isZero();
        assertThat(plan.gc().installed()).as("no collector is selected here").isFalse();
        assertThat(inventory().publishedAt(ECOSYSTEM, COORDINATE, "1.0.0")).as("a dry run evicts nothing")
                .isPresent();
    }

    @Test
    void a_sweep_evicts_what_the_policy_names_spares_a_pin_and_is_audited() throws Exception {
        recordThreeVersions();
        controller.setRetention(REPO, "1", "", "", "", null, request(), Servlets.response().servlet());
        controller.pin(REPO, ECOSYSTEM, COORDINATE, "1.0.0", null, request(), Servlets.response().servlet());

        controller.cleanup(REPO, "key", request(), Servlets.response().servlet());
        RepositoryCleanup.View report = settled(false);

        assertThat(report.state()).isEqualTo("done");
        assertThat(report.evictedCount()).as("the pinned oldest version is spared").isEqualTo(1);
        assertThat(inventory().publishedAt(ECOSYSTEM, COORDINATE, "1.0.0")).as("pinned").isPresent();
        assertThat(inventory().publishedAt(ECOSYSTEM, COORDINATE, "1.1.0")).as("evicted").isEmpty();
        assertThat(inventory().publishedAt(ECOSYSTEM, COORDINATE, "1.2.0")).as("the newest is kept").isPresent();
        assertThat(audit.rows()).last().satisfies(row -> {
            assertThat(row.action()).isEqualTo(AuditActions.REPOSITORY_CLEANUP);
            assertThat(row.target()).isEqualTo(REPO + " (started)");
        });
    }

    @Test
    void a_sweep_over_an_ecosystem_no_format_places_refuses_rather_than_deleting() throws Exception {
        recordThreeVersions(UNPLACED);
        controller.setRetention(REPO, "1", "", "", "", null, request(), Servlets.response().servlet());

        controller.cleanup(REPO, null, request(), Servlets.response().servlet());
        RepositoryCleanup.View refused = settled(false);
        assertThat(refused.state()).as("the run stops rather than deleting").isEqualTo("failed");
        assertThat(refused.failure()).contains("no installed format can place");

        assertThat(inventory().publishedAt(UNPLACED, COORDINATE, "1.0.0")).as("nothing was evicted").isPresent();
        assertThat(audit.actions()).as("the retention change names all four rules, one set and three cleared, and "
                        + "the sweep is recorded as started though it went on to refuse")
                .containsExactly(AuditActions.SETTING_SET, AuditActions.SETTING_CLEAR, AuditActions.SETTING_CLEAR,
                        AuditActions.SETTING_CLEAR, AuditActions.REPOSITORY_CLEANUP);
    }

    @Test
    void forgetting_an_ecosystem_nothing_places_removes_its_records_and_is_audited() throws Exception {
        recordThreeVersions(UNPLACED);

        Map<String, Object> forgotten = controller.forgetEcosystem(REPO, UNPLACED, null, request(),
                Servlets.response().servlet());

        assertThat(forgotten).containsEntry("ecosystem", UNPLACED);
        assertThat((Long) forgotten.get("removed")).isPositive();
        assertThat(inventory().publishedAt(UNPLACED, COORDINATE, "1.2.0")).isEmpty();
        assertThat(audit.actions()).containsExactly(AuditActions.REPOSITORY_FORGET_ECOSYSTEM);
    }

    @Test
    void an_ecosystem_an_installed_format_places_is_not_forgotten() throws Exception {
        recordThreeVersions();
        Servlets.Response response = Servlets.response();

        assertThat(controller.forgetEcosystem(REPO, ECOSYSTEM, null, request(), response.servlet())).isNull();

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body()).isNotBlank();
        assertThat(inventory().publishedAt(ECOSYSTEM, COORDINATE, "1.2.0")).isPresent();
        assertThat(audit.rows()).isEmpty();
    }

    /** A request the default tenant answers, presenting no key. */
    private static HttpServletRequest request() {
        return Servlets.request("POST", "/api/repository/pin");
    }
}
