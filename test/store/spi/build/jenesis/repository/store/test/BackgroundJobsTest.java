package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.BackgroundJobs;
import build.jenesis.repository.store.StoreBindings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A job started over a store a deployment bound its jobs to ends with the deployment: closing the jobs interrupts it
 * and waits for it, and no job is started after. A store nothing bound runs its job untracked.
 */
class BackgroundJobsTest {

    @TempDir
    Path root;

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
    }

    @Test
    void closing_the_jobs_interrupts_a_running_job_and_waits_for_it_to_stop() throws Exception {
        BackgroundJobs jobs = new BackgroundJobs();
        ArtifactStore store = StoreBindings.of(BackgroundJobs.class, jobs).over(store()).scope("tenant");
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread job = BackgroundJobs.start(store, "a-long-job", () -> {
            started.countDown();
            try {
                Thread.sleep(Duration.ofMinutes(5));
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        started.await();

        jobs.close();

        assertThat(interrupted).as("interrupted, through a scoped view of the bound store").isTrue();
        assertThat(job.isAlive()).as("and waited for").isFalse();
        assertThatThrownBy(() -> BackgroundJobs.start(store, "a-late-job", () -> { }))
                .as("nothing starts for a deployment going away").isInstanceOf(IllegalStateException.class);
    }

    @Test
    void a_store_nothing_bound_runs_its_job_untracked() throws Exception {
        AtomicBoolean ran = new AtomicBoolean();
        BackgroundJobs.start(store(), "an-untracked-job", () -> ran.set(true)).join();
        assertThat(ran).isTrue();
    }
}
