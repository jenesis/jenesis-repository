package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cleanup.task.CleanupTask;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.maintenance.UnitFailures;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Import-job records stop growing: before this, only a manual dismiss removed a finished migration's
 * {@code imports/<id>} record and its remembered {@code import-source/<id>}. The cleanup pass now stamps an
 * {@code import-expiry/<id>} marker when it first observes a job terminal and auto-dismisses a full TTL later -
 * never sooner than the TTL after finishing, never touching a running job, and cleaning its own marker up when the
 * job was dismissed by hand or resumed.
 */
class ImportJobReapTest {

    private static final Instant NOW = Instant.parse("2026-06-27T00:00:00Z");

    @TempDir
    Path root;

    private ArtifactStore store;
    private CleanupTask task;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        task = new CleanupTask(Duration.ofHours(1));
    }

    @Test
    void a_finished_job_is_dismissed_one_ttl_after_first_seen_terminal() throws IOException {
        job("done", "completed");
        store.write("import-source/done", new ByteArrayInputStream("url=x".getBytes(StandardCharsets.UTF_8)));

        task.repository(context(NOW, null));
        assertThat(store.readVersioned("imports/done")).as("first observation only stamps").isPresent();
        assertThat(store.readVersioned("import-expiry/done")).isPresent();

        task.repository(context(NOW.plus(Duration.ofDays(3)), null));
        assertThat(store.readVersioned("imports/done")).as("within the default 7-day TTL").isPresent();

        task.repository(context(NOW.plus(Duration.ofDays(8)), null));
        assertThat(store.readVersioned("imports/done")).isEmpty();
        assertThat(store.readVersioned("import-source/done")).isEmpty();
        assertThat(store.readVersioned("import-expiry/done")).isEmpty();
    }

    @Test
    void a_running_job_is_never_touched_and_a_resume_resets_the_marker() throws IOException {
        job("failed-once", "failed");
        task.repository(context(NOW, null));
        assertThat(store.readVersioned("import-expiry/failed-once")).isPresent();

        job("failed-once", "running");                 // the operator resumed it
        task.repository(context(NOW.plus(Duration.ofDays(30)), null));
        assertThat(store.readVersioned("imports/failed-once")).as("running jobs are never dismissed").isPresent();
        assertThat(store.readVersioned("import-expiry/failed-once")).as("stale marker reset on resume").isEmpty();
    }

    @Test
    void a_hand_dismissed_jobs_marker_is_cleaned_up_and_zero_ttl_disables() throws IOException {
        store.writeVersioned("import-expiry/orphan", NOW.toString().getBytes(StandardCharsets.UTF_8), null);
        job("kept", "completed");

        task.repository(context(NOW, "PT0S"));
        assertThat(store.readVersioned("imports/kept")).as("PT0S disables the auto-dismiss").isPresent();
        assertThat(store.readVersioned("import-expiry/kept")).isEmpty();
        assertThat(store.readVersioned("import-expiry/orphan")).as("PT0S leaves the sweep entirely off").isPresent();

        task.repository(context(NOW, null));
        assertThat(store.readVersioned("import-expiry/orphan")).as("orphan marker of a dismissed job").isEmpty();
    }

    private void job(String id, String state) throws IOException {
        String json = "{\"state\":\"" + state + "\",\"imported\":3,\"skipped\":0,\"cursor\":null}";
        store.write("imports/" + id, new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    /** A minimal pass context over the temp store; {@code import-job-ttl} is the only setting the reap reads. */
    private RepositoryContext context(Instant now, String ttl) {
        return new RepositoryContext() {
            @Override
            public UnitFailures failures(String work, String consequence) {
                return new UnitFailures(work, consequence);
            }

            @Override
            public String tenant() {
                return "acme";
            }

            @Override
            public String repository() {
                return "app";
            }

            @Override
            public ArtifactStore store() {
                return store;
            }

            @Override
            public UnaryOperator<String> config() {
                return key -> "import-job-ttl".equals(key) ? ttl : null;
            }

            @Override
            public Instant now() {
                return now;
            }

            @Override
            public void gauge(String name, String description, Map<String, String> tags, double value) {
            }
        };
    }
}
