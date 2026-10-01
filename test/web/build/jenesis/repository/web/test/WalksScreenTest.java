package build.jenesis.repository.web.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.management.web.WalksAdminController;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.kernel.PinnedSettings;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.task.WalkRuns;
import build.jenesis.repository.walk.task.WalkSchedules;
import build.jenesis.repository.walk.web.WalksController;
import build.jenesis.repository.servlet.testkit.Servlets;
import build.jenesis.repository.web.testkit.Web;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The walks screen and its API twin over a real store: an entry saved on the screen is what the overview then
 * lists and what the stored {@code walks} document says, a document that would not read back is refused with nothing
 * written, a removed entry is gone, and a walk asked for now stands as a request with the asker recorded. Both
 * surfaces reach {@link WalkRuns}, so the API's overview is asserted to carry what the screen saved.
 */
class WalksScreenTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private Repositories repositories;
    private Settings settings;
    private Web.Recording audit;
    private WalksController screen;
    private WalksAdminController api;

    @BeforeEach
    void wire() throws IOException {
        store = Web.store(root);
        repositories = Web.repositories(store);
        StandardEnvironment environment = new StandardEnvironment();
        settings = new Settings(store);
        audit = Web.audit();
        // The operator scope a walk request is recorded in, named rather than inherited from the default tenant.
        RepositoryProperties properties = new RepositoryProperties();
        properties.setOperatorTenant("ops");
        screen = new WalksController(settings, new PinnedSettings(environment), environment, store, audit,
                Web.scheduler(repositories, store), properties);
        api = new WalksAdminController(store, audit, settings, new PinnedSettings(environment), environment,
                Web.scheduler(repositories, store), Web.routing(repositories, "acme", "ops"));
    }

    @Test
    void the_overview_is_the_schedule_the_node_runs_when_the_environment_sets_it() throws IOException {
        // An operator's variable outranks the stored document and the default, so the scheduler runs it - and the
        // overview, which an operator reads to learn when the next walk is, says the same.
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("operator", Map.of("jenrepo.walks",
                "[{\"name\":\"rebuild\",\"cron\":\"*/2 * * * * *\",\"consumers\":[\"*\"]}]")));
        WalksAdminController pinned = new WalksAdminController(store, audit, settings,
                new PinnedSettings(environment), environment, Web.scheduler(repositories, store),
                Web.routing(repositories, "acme", "ops"));

        assertThat(pinned.walks().entries()).extracting(WalkRuns.Entry::cron).containsExactly("*/2 * * * * *");
    }

    @Test
    void the_screen_renders_the_default_schedule_before_anything_is_saved() throws IOException {
        ExtendedModelMap model = new ExtendedModelMap();

        assertThat(screen.walks(model)).isEqualTo("walks-screen/list");

        WalkRuns.Overview overview = (WalkRuns.Overview) model.get("overview");
        assertThat(overview.requests()).as("nothing has been asked for").isEmpty();
        assertThat(model.get("defaultDocument")).isEqualTo(WalkSchedules.DEFAULT);
    }

    @Test
    void a_saved_entry_is_stored_and_listed_with_its_next_run() throws IOException {
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        String view = screen.save(" nightly ", "0 0 2 * * *", "on", List.of("rollup"), redirect);

        assertThat(view).isEqualTo("redirect:/ui/walks");
        assertThat(redirect.getFlashAttributes().get("message"))
                .isEqualTo("Saved the walk 'nightly'. It runs at 0 0 2 * * *.");
        assertThat(WalkSchedules.parse(settings.getOrDefault(WalkSchedules.SETTING, null)))
                .contains(new WalkSchedules.Entry("nightly", "0 0 2 * * *", List.of("rollup"), true));
        assertThat(api.walks().entries()).filteredOn(entry -> entry.name().equals("nightly")).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.enabled()).isTrue();
                    assertThat(entry.next()).as("an enabled entry says when it runs next").isPresent();
                    assertThat(entry.run()).as("it has not run on this node").isEmpty();
                });
    }

    @Test
    void an_entry_saved_switched_off_says_so_and_has_no_next_run() throws IOException {
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        screen.save("paused", "0 0 4 * * *", null, List.of("rollup"), redirect);

        assertThat(redirect.getFlashAttributes().get("message")).asString().endsWith("switched off.");
        assertThat(api.walks().entries()).filteredOn(entry -> entry.name().equals("paused")).singleElement()
                .satisfies(entry -> assertThat(entry.next()).isEmpty());
    }

    @Test
    void a_malformed_cron_expression_is_refused_and_nothing_is_written() throws IOException {
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        screen.save("broken", "every tuesday", "on", List.of("rollup"), redirect);

        assertThat(redirect.getFlashAttributes().get("error")).asString()
                .startsWith("Nothing saved: ").contains("'broken'");
        assertThat(settings.overrides()).doesNotContainKey(WalkSchedules.SETTING);
    }

    @Test
    void an_entry_that_names_no_consumer_is_refused() throws IOException {
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        screen.save("empty", "0 0 2 * * *", "on", null, redirect);

        assertThat(redirect.getFlashAttributes().get("error")).asString().contains("names no consumer");
        assertThat(settings.overrides()).doesNotContainKey(WalkSchedules.SETTING);
    }

    @Test
    void a_removed_entry_is_gone_from_the_document() throws IOException {
        screen.save("nightly", "0 0 2 * * *", "on", List.of("rollup"), new RedirectAttributesModelMap());
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        screen.remove("nightly", redirect);

        assertThat(redirect.getFlashAttributes().get("message")).isEqualTo("Removed the walk 'nightly'.");
        assertThat(WalkSchedules.parse(settings.getOrDefault(WalkSchedules.SETTING, null)))
                .noneMatch(entry -> entry.name().equals("nightly"));
    }

    @Test
    void a_walk_asked_for_on_the_screen_stands_as_a_request_and_names_who_asked() throws IOException {
        screen.run(() -> "operator", new RedirectAttributesModelMap());

        assertThat(api.walks().requests()).singleElement()
                .satisfies(pending -> assertThat(pending.reason()).isEqualTo("requested by operator"));
        assertThat(audit.rows()).singleElement().satisfies(row -> {
            assertThat(row.action()).isEqualTo(AuditActions.WALKS_RUN);
            assertThat(row.tenant()).as("recorded in the operator scope").isEqualTo("ops");
            assertThat(row.actor()).isEqualTo("operator");
        });
    }

    @Test
    void a_walk_asked_for_without_a_principal_is_recorded_as_anonymous() throws IOException {
        screen.run(null, new RedirectAttributesModelMap());

        assertThat(audit.rows()).singleElement().satisfies(row -> assertThat(row.actor()).isEqualTo("anonymous"));
    }

    @Test
    void the_api_records_a_hash_of_the_key_never_the_key() throws IOException {
        WalkRuns.Overview overview = api.run("secret-key", Servlets.request("POST", "/api/admin/walks/run"));

        assertThat(overview.requests()).hasSize(1);
        assertThat(audit.rows()).singleElement().satisfies(row -> {
            assertThat(row.actor()).isNotEqualTo("secret-key").doesNotContain("secret");
            assertThat(row.action()).isEqualTo(AuditActions.WALKS_RUN);
            assertThat(row.tenant()).as("in the trail of the tenant the call answers for, which it reads back")
                    .isEqualTo("acme");
        });
    }
}
