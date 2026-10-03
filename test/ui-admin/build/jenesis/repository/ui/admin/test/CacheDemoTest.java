package build.jenesis.repository.ui.admin.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.cache.storage.testkit.CacheStorages;
import build.jenesis.repository.demo.Demo;
import build.jenesis.repository.demo.DemoContributor;
import build.jenesis.repository.server.kernel.SettingsEditor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.ui.admin.CacheDemo;
import build.jenesis.repository.ui.store.CacheService;
import build.jenesis.repository.ui.store.SettingsAdmin;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The build cache's demo content: its plan names the projects before anything is made, and a load leaves each project
 * created as the operator, holding the outputs it stored and already counted, so the projects screen shows figures
 * rather than a project nobody has counted.
 */
class CacheDemoTest {

    private static final String TENANT = "acme";
    private static final String OPERATOR = "github/ada";

    @TempDir
    private Path cacheRoot;

    private CacheStorage root;
    private ArtifactStore store;
    private final List<String> audited = new ArrayList<>();
    private final List<String> made = new ArrayList<>();

    @BeforeEach
    void setUp() {
        root = CacheStorages.filesystem(cacheRoot);
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? cacheRoot.toString() : null);
    }

    private final AuditTrail audit = new AuditTrail() {
        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public void record(String tenant, String actor, String action, String target) {
            audited.add(tenant + " " + actor + " " + action + " " + target);
        }

        @Override
        public List<Event> query(String tenant, Instant from, Instant to, String action) {
            return List.of();
        }
    };

    @Test
    void the_plan_names_a_project_for_the_jenesis_build_tool_and_one_for_gradle() {
        DemoContributor.Plan plan = demo().plan();

        assertThat(plan.empty()).isFalse();
        assertThat(plan.repositories()).as("the run creates no repository for the cache").isEmpty();
        assertThat(String.join("\n", plan.creates())).contains("jenesis_build (jenesis)", "payments_service (gradle)");
    }

    @Test
    void a_load_leaves_every_project_created_as_the_operator_filled_and_counted() throws IOException {
        demo().load(new Recording());

        CacheService projects = new CacheService(root, AuditTrail.none(), () -> TENANT, () -> OPERATOR,
                new SettingsAdmin(store));
        assertThat(projects.listProjects()).extracting(CacheService.ProjectSummary::name)
                .containsExactlyInAnyOrder("jenesis_build", "payments_service");
        for (CacheService.ProjectSummary project : projects.listProjects()) {
            assertThat(project.stats().known()).as("%s is counted", project.name()).isTrue();
            assertThat(project.stats().counting()).isFalse();
            assertThat(project.stats().entryCount()).as("%s holds what was stored", project.name()).isPositive();
            assertThat(project.stats().totalBytes()).isPositive();
            assertThat(project.description()).startsWith("Demo: ");
        }
        assertThat(audited).as("each creation is the operator's, in the demo's tenant")
                .anySatisfy(line -> assertThat(line).startsWith(TENANT + " " + OPERATOR + " ").endsWith(" jenesis_build"));
        assertThat(made).as("what was made is reported to the run").allSatisfy(line ->
                assertThat(line).startsWith("DONE ")).hasSize(4);
    }

    @Test
    void a_project_that_exists_already_is_reported_and_the_rest_still_load() throws IOException {
        root.scope(TENANT).createProject("jenesis_build", "jenesis", "");

        demo().load(new Recording());

        assertThat(made).first().satisfies(line -> assertThat(line).startsWith("FAILED ")
                .contains("jenesis_build").contains("exists"));
        assertThat(made).last().satisfies(line -> assertThat(line).startsWith("DONE ").contains("payments_service"));
    }

    private CacheDemo demo() {
        try {
            return new CacheDemo(root, store, SettingsEditor.over(store, _ -> Optional.empty(), audit, _ -> null),
                    audit);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /** The run as the cache's content sees it: the tenant, the operator, and what it reports. */
    private final class Recording implements Demo {

        @Override
        public String tenant() {
            return TENANT;
        }

        @Override
        public String actor() {
            return OPERATOR;
        }

        @Override
        public String setting(String key) {
            return "";
        }

        @Override
        public boolean settings(Map<String, String> values) {
            throw new AssertionError("the cache's content switches no setting");
        }

        @Override
        public Outcome publish(String repository, String path, InputStream body) {
            throw new AssertionError("the cache's content publishes nothing");
        }

        @Override
        public Outcome fetch(String repository, String path) {
            throw new AssertionError("the cache's content reads nothing");
        }

        @Override
        public void request(String pass, String reason, Duration after) {
            throw new AssertionError("the cache's content asks for no pass");
        }

        @Override
        public void skipped(String what, String why) {
            made.add("SKIPPED " + what + ": " + why);
        }

        @Override
        public Outcome made(String what, Outcome outcome, String detail) {
            made.add(outcome + " " + what + ": " + detail);
            return outcome;
        }
    }
}
