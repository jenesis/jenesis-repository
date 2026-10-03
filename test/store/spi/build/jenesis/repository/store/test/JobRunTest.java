package build.jenesis.repository.store.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.JobState;
import build.jenesis.repository.store.Lease;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A background job's record is believed only while a run holds it: one left saying running by a node that stopped
 * reads interrupted, one that says the job ended reads running until its run lets go, a job a run holds cannot be
 * taken by a second, and a run whose hold was taken over stops at its next write instead of writing over the run that
 * replaced it.
 */
class JobRunTest {

    private static final byte[] RUNNING = "running".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
    }

    @Test
    void a_record_saying_running_is_believed_only_while_a_run_holds_it() throws IOException {
        store.write("imports/orphaned", new ByteArrayInputStream(RUNNING));
        assertThat(JobState.effective(store, "imports", "orphaned", JobState.RUNNING))
                .as("no run holds it: its node stopped").isEqualTo(JobState.INTERRUPTED);
        assertThat(JobState.effective(store, "imports", "orphaned", "failed")).as("an ended job reads as it ended")
                .isEqualTo("failed");

        try (JobState.Run _ = JobState.Run.claim(store, "imports", "live", RUNNING, null)) {
            assertThat(JobState.effective(store, "imports", "live", JobState.RUNNING)).isEqualTo(JobState.RUNNING);
            assertThatThrownBy(() -> JobState.Run.claim(store, "imports", "live", RUNNING,
                    store.readVersioned("imports/live").orElseThrow().token()))
                    .as("a job a run holds is not taken by a second").isInstanceOf(JobState.Running.class);
        }
        assertThat(JobState.effective(store, "imports", "live", JobState.RUNNING))
                .as("closed without writing an end, it reads interrupted").isEqualTo(JobState.INTERRUPTED);
    }

    @Test
    void an_ended_record_reads_running_until_its_run_lets_go() throws IOException {
        JobState.Run closing = JobState.Run.claim(store, "imports", "closing", RUNNING, null);
        closing.write("completed".getBytes(StandardCharsets.UTF_8));

        assertThat(JobState.effective(store, "imports", "closing", "completed"))
                .as("its run has not let go, so a resume asked for now would meet the hold").isEqualTo(JobState.RUNNING);
        closing.close();
        assertThat(JobState.effective(store, "imports", "closing", "completed")).isEqualTo("completed");
        try (JobState.Run _ = JobState.Run.claim(store, "imports", "closing", RUNNING,
                store.readVersioned("imports/closing").orElseThrow().token())) {
            assertThat(JobState.effective(store, "imports", "closing", JobState.RUNNING))
                    .as("a resume asked for once the record reads ended takes the job").isEqualTo(JobState.RUNNING);
        }
    }

    @Test
    void a_run_whose_hold_was_taken_over_stops_at_its_next_write() throws IOException {
        try (JobState.Run run = JobState.Run.claim(store, "exports", "taken", RUNNING, null)) {
            run.write("running, a checkpoint later".getBytes(StandardCharsets.UTF_8));
            // Its node paused past the lease, and a resume elsewhere took the job over.
            store.write(Lease.objectKey("exports-taken"), new ByteArrayInputStream(("job/elsewhere\n"
                    + Instant.now().plus(Duration.ofMinutes(3))).getBytes(StandardCharsets.UTF_8)));
            store.write("exports/taken",
                    new ByteArrayInputStream("the resume's record".getBytes(StandardCharsets.UTF_8)));

            assertThatThrownBy(() -> run.write("completed".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(JobState.Lost.class);
            assertThat(new String(store.readVersioned("exports/taken").orElseThrow().content(), StandardCharsets.UTF_8))
                    .as("the record stays the run's that took it over").isEqualTo("the resume's record");
        }
    }

    @Test
    void a_claim_against_a_record_that_changed_releases_what_it_took() throws IOException {
        store.write("imports/reaped", new ByteArrayInputStream("failed".getBytes(StandardCharsets.UTF_8)));
        Object read = store.readVersioned("imports/reaped").orElseThrow().token();
        store.write("imports/reaped", new ByteArrayInputStream("dismissed".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> JobState.Run.claim(store, "imports", "reaped", RUNNING, read))
                .isInstanceOf(JobState.Dismissed.class);
        assertThat(JobState.effective(store, "imports", "reaped", JobState.RUNNING))
                .as("the hold it took is given back").isEqualTo(JobState.INTERRUPTED);
    }
}
