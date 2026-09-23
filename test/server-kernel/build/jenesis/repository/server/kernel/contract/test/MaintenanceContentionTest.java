package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;

import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.cleanup.RetentionProvider;
import build.jenesis.repository.store.Lease;
import build.jenesis.repository.server.kernel.LeaseGuard;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.MaintenanceScheduler;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.server.kernel.TaskSchedule;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.staging.StagingProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The maintenance kernel's <b>contention contract</b> - the ten contention properties C1-C10
 * &sect;7, driven through the live {@link MaintenanceScheduler} (and through {@link LeaseGuard} where determinism needs
 * it) rather than only against {@code Lease}. The spike's finding was precisely that lease behaviour was proven on
 * {@code Lease} and almost never <em>through a running pass</em>: the renewal timer, its cadence, its cancellation and
 * what a lost lease does to the pass were all untested, and three of them were also wrong.
 *
 * <p>Each test below names the property it pins and, where the property did not hold before, what the old code
 * did instead - so a future reader can tell a regression test from a characterisation test.
 *
 * <p>The sibling {@code MaintenanceSchedulerTest} keeps the functional pass/fan-out/observability coverage; this class
 * is only about who may run a pass, when, and what happens when that answer changes mid-pass.
 */
class MaintenanceContentionTest {

    private static final Instant NOW = Instant.parse("2026-06-27T00:00:00Z");

    /** The worker thread's name - {@link MaintenanceScheduler} starts exactly one, ever. The bounded fan-out pool and
     *  the lease-renewal timer carry {@code -worker}/{@code -lease} suffixes, so an exact-name match counts loops. */
    private static final String WORKER = "jenesis-repository-maintenance";

    /** C4's rival probes this many times, {@link #PROBE_GAP} apart, while the pass is held open. Their product is a
     *  <em>lower</em> bound on the pass's life - four seconds against a two-second ttl - so the renewal timer must
     *  have fired repeatedly for every probe to be refused. Attempts, never a deadline: a slow machine lengthens the
     *  pass instead of skipping probes, so it can only strengthen the claim. */
    private static final int PROBES = 40;
    private static final Duration PROBE_GAP = Duration.ofMillis(100);

    @TempDir
    Path root;

    private ArtifactStore store;
    private Repositories repositories;

    @BeforeEach
    void setUp() throws IOException {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        RepositoryProperties properties = new RepositoryProperties();
        properties.setProxyEnabled(false);
        LiveConfig live = new LiveConfig(new Settings(store), properties, AdvisorySource.none(), _ -> null);
        repositories = new Repositories(store, Authorization.anonymous(), live,
                StagingProvider.resolve(_ -> null), RetentionProvider.resolve(_ -> null));
    }

    // --- C1: start() is idempotent --------------------------------------------------------------------------------

    @Test
    void c1_a_second_start_does_not_create_a_second_worker_loop() throws IOException {
        // BEFORE start() called startWorker() unconditionally while refresh() guarded on
        // `thread == null || !thread.isAlive()`. A second start() therefore overwrote the thread field while the first
        // loop kept running against a `running` flag that was still true - two loops on one node, each taking and
        // releasing the same leases and each re-arming its own due map. Latent (only Spring's initMethod calls start),
        // but a latent second scheduler is exactly what design gate 3 forbids.
        int before = workerLoops();
        try (MaintenanceScheduler scheduler = new MaintenanceScheduler(repositories, store,
                List.of(idle("c1")), key -> null, Duration.ofMinutes(10), null)) {
            scheduler.start();
            scheduler.start();
            scheduler.start();

            assertThat(workerLoops() - before)
                    .as("three start() calls must leave exactly one worker loop, not three")
                    .isEqualTo(1);
            assertThat(scheduler.alive()).as("and the one loop is the live one").isTrue();
        }
    }

    @Test
    void c1_start_and_refresh_share_one_guard_so_neither_order_yields_two_loops() throws IOException {
        // start() and refresh() are the only two ways a worker is started, and both now go through the same guard.
        // The interleaving below is the live one: boot with nothing enabled (start() logs and returns), a pass is
        // toggled on (refresh() starts the loop), the settings tick again (refresh() must not start a second).
        int before = workerLoops();
        List<MaintenanceTask> enabled = new ArrayList<>();
        try (MaintenanceScheduler scheduler = new MaintenanceScheduler(repositories, store,
                List.copyOf(enabled), () -> List.copyOf(enabled), key -> null, Duration.ofMinutes(10), null)) {
            scheduler.start();
            assertThat(scheduler.alive()).as("nothing is enabled, so no loop runs yet").isFalse();

            enabled.add(idle("c1b"));
            scheduler.refresh();
            scheduler.refresh();
            scheduler.start();

            assertThat(workerLoops() - before).as("one loop, whichever order start and refresh arrive in").isEqualTo(1);
            assertThat(scheduler.alive()).isTrue();
        }
    }

    // --- C2: two nodes, one exclusive pass; the refused node skips and is NOT a failure ---------------------------

    @Test
    void c2_a_refused_exclusive_pass_is_a_skip_not_a_failure() throws IOException {
        new StoreRepositoryInventory(repositories.store("default", "alpha")).record("Maven", "org:lib", "1.0", NOW);
        Lease rival = new Lease(store, Duration.ofMinutes(10));
        assertThat(rival.acquire("c2", "rival-node", NOW)).isTrue();

        MeterRegistry registry = new SimpleMeterRegistry();
        AtomicInteger units = new AtomicInteger();
        Map<String, TaskSchedule.TaskRun> runs;
        try (MaintenanceScheduler scheduler = new MaintenanceScheduler(repositories, store,
                List.of(counting("c2", units)), key -> null, Duration.ofMinutes(10), registry)) {
            scheduler.runNow(NOW);
            runs = scheduler.taskRuns();
        }

        assertThat(units.get()).as("the rival owns this pass; the body must not run here").isZero();
        assertThat(registry.find("jenreg.maintenance.failures").counter())
                .as("a refused acquisition is the normal fleet behaviour - exactly one node sweeps and the others "
                        + "skip - so it must not be counted as a failure")
                .isNull();
        assertThat(runs.get("c2"))
                .as("the skipped pass records no run at all, so the observability surface reports it pending rather "
                        + "than FAILED on every node but the one that happens to hold the lease")
                .isNull();
    }

    // --- C4: renewal keeps a rival out for the whole pass ---------------------------------------------------------

    @Test
    void c4_renewal_keeps_a_rival_out_for_a_pass_that_outlives_its_ttl() throws Exception {
        new StoreRepositoryInventory(repositories.store("default", "alpha")).record("Maven", "org:lib", "1.0", NOW);
        Duration ttl = Duration.ofSeconds(2);          // renewal cadence is ttl/2 = 1s

        CountDownLatch inPass = new CountDownLatch(1);
        CountDownLatch finishPass = new CountDownLatch(1);
        MaintenanceTask blocking = task("c4", context -> {
            inPass.countDown();
            hold(finishPass);
        });

        Lease rival = new Lease(store, ttl);
        List<Boolean> stolen = Collections.synchronizedList(new ArrayList<>());
        try (MaintenanceScheduler scheduler = new MaintenanceScheduler(repositories, store,
                List.of(blocking), key -> null, ttl, null)) {
            // Wall-clock, not the suite's fixed instant: the lease is acquired with the pass's own `now` while the
            // renewal timer necessarily runs on the wall clock, so a pass driven from a historical instant would hand
            // out an already-expired lease. The scheduled loop and the admin endpoint both pass Instant.now().
            Thread sweeper = new Thread(() -> scheduler.runNow(Instant.now()), "c4-sweeper");
            sweeper.start();
            assertThat(inPass.await(10, TimeUnit.SECONDS)).as("the pass started").isTrue();

            // Probe throughout the pass: every acquisition must be refused, which is only true while the holder keeps
            // renewing. This is the leg LeaseTest could not cover - it exercised Lease.renew directly, never the
            // scheduler's renewal timer, its ttl/2 cadence or its cancellation.
            //
            // The pass now ends when this test says so, not on a clock, and that is what makes the probing sound.
            // The old form ran this loop for a fixed 4s inside a 5s pass and then asserted over whatever it had
            // recorded: a cold JVM that completed no probe left an empty list, doesNotContain(true) passed, and the
            // cell said nothing about renewal either way - which is why it had to carry a "the probe really ran"
            // count beside it. Holding the pass open retires both. Every probe is inside the pass, so none can land
            // in the instant after release and read a free lease; and PROBES x PROBE_GAP is now a LOWER bound on how
            // long the pass runs, so a slow machine stretches the pass past the ttl rather than skipping probes.
            for (int probe = 0; probe < PROBES; probe++) {
                stolen.add(rival.acquire("c4", "rival-node", Instant.now()));
                Thread.sleep(PROBE_GAP.toMillis());
            }
            finishPass.countDown();
            sweeper.join(30_000L);

            assertThat(stolen).as("a rival was refused at every one of the probes, across a pass held open well past "
                    + "the raw ttl - the renewal timer kept the expiry ahead of the wall clock").doesNotContain(true);

            // C6, through a pass rather than through Lease: the completed pass released, so the next acquirer takes
            // the lock at once instead of waiting out the ttl.
            assertThat(rival.acquire("c4", "rival-node", Instant.now()))
                    .as("the completed pass released its lease immediately").isTrue();
        }
    }

    // --- C5: a lease lost mid-pass is counted AND bounds the pass -------------------------------------------------

    @Test
    void c5_a_lease_lost_mid_pass_fails_the_pass_and_stops_further_units() throws IOException {
        // BEFORE renew() logged a WARNING and nothing else. The pass ran to completion over every remaining
        // (tenant, repository) unit, its failure counter and its TaskRun.failed flag untouched - so the dashboard
        // reported a clean sweep while two nodes swept the same store. Both halves are asserted here.
        for (String repository : List.of("a-first", "b-second", "c-third")) {
            new StoreRepositoryInventory(repositories.store("default", repository)).record("Maven", "org:lib", "1.0", NOW);
        }
        Duration ttl = Duration.ofMillis(600);         // renewal cadence 300ms
        MeterRegistry registry = new SimpleMeterRegistry();
        List<String> swept = Collections.synchronizedList(new ArrayList<>());
        MaintenanceTask sweeping = task("c5", context -> {
            swept.add(context.repository());
            if (swept.size() == 1) {
                // Take the lock away under the running holder: the next renewal reads no lock at all and refuses,
                // which is the "a rival stole it after we stalled past the ttl" signal.
                store.delete(LeaseGuard.object("c5"));
                // Deliberately a wait on the clock, and the only one left in this class. The signal this pass is
                // waiting for - LeaseGuard flipping its pass to "lost" - is refused-renewal state internal to the
                // guard, unreachable from a task body, and a refused renewal writes nothing to the store either, so
                // there is no observable to drive until. What makes it safe rather than vacuous: the wait is stated
                // as a multiple of the guard's own renewal cadence rather than a magic number, so it cannot drift
                // when the ttl changes, and every assertion below fails loudly (and names the sweep) if it was still
                // too short - none of them can pass over a pass that never noticed. The multiple is sized for a
                // saturated machine, not an idle one: at five cadences the hosted runner starved the guard's timer
                // thread past the wait (2026-09-05, every unit ran), so it is twenty - six seconds at this ttl,
                // paid once, against a renewal that fires in a third of a second when the box is quiet.
                sleep(ttl.dividedBy(2).multipliedBy(20));
            }
        });

        // One fan-out worker, so the units run in a defined order and the stop is observable.
        MaintenanceScheduler scheduler = new MaintenanceScheduler(repositories, store, List.of(sweeping),
                key -> null, ttl, registry, 1);
        scheduler.runNow(NOW);
        scheduler.close();

        assertThat(swept).as("the pass stopped submitting units the moment single-writer status was lost, bounding "
                        + "the window in which two nodes sweep the same store to the unit already running")
                .containsExactly("a-first");
        assertThat(registry.get("jenreg.maintenance.failures").tag("task", "c5").counter().count())
                .as("losing a lease you held is a pass failure, not a skip: it is counted").isEqualTo(1.0);
        TaskSchedule.TaskRun run = scheduler.taskRuns().get("c5");
        assertThat(run).isNotNull();
        assertThat(run.failed()).as("and the observability surface reports the pass FAILED").isTrue();
        assertThat(run.failures()).isEqualTo(1L);
    }

    @Test
    void c5_a_pass_that_keeps_its_lease_is_not_reported_as_having_lost_it() throws IOException {
        // The negative control for the leg above: the same shape with the lock left alone must stay clean, so the
        // failure the previous test observes is the lost lease and not the sleep, the fan-out width or the ttl.
        for (String repository : List.of("a-first", "b-second", "c-third")) {
            new StoreRepositoryInventory(repositories.store("default", repository)).record("Maven", "org:lib", "1.0", NOW);
        }
        MeterRegistry registry = new SimpleMeterRegistry();
        List<String> swept = Collections.synchronizedList(new ArrayList<>());
        MaintenanceScheduler scheduler = new MaintenanceScheduler(repositories, store,
                List.of(task("c5b", context -> swept.add(context.repository()))),
                key -> null, Duration.ofMillis(600), registry, 1);
        scheduler.runNow(NOW);
        scheduler.close();

        assertThat(swept).as("every unit ran").containsExactlyInAnyOrder("a-first", "b-second", "c-third");
        assertThat(registry.find("jenreg.maintenance.failures").counter())
                .as("and nothing was counted").isNull();
    }

    // --- C9: an exclusive pass hands the lease round ---------------------------------------------------------------

    @Test
    void c9_a_node_that_ran_an_exclusive_pass_is_not_due_again_before_one_interval_after_it_finished()
            throws IOException {
        // A pass longer than its interval used to be due again the instant it ended, so the node that had just
        // released the lease re-took it before a peer polling at the same cadence ever found it free - measured in
        // the fleet as a restarted node never joining a minute-long walk repeating every two seconds. The schedule
        // yields one interval after the pass finished; a non-exclusive pass, which no peer waits on, does not.
        Duration interval = Duration.ofMinutes(10);
        MaintenanceTask exclusive = new MaintenanceTask() {
            @Override
            public String name() {
                return "c9-exclusive";
            }

            @Override
            public Duration interval() {
                return interval;
            }

            @Override
            public Exclusion exclusion() {
                return Exclusion.LEASE;
            }

            @Override
            public void repository(RepositoryContext context) {
            }
        };
        MaintenanceTask shared = new MaintenanceTask() {
            @Override
            public String name() {
                return "c9-shared";
            }

            @Override
            public Duration interval() {
                return interval;
            }

            @Override
            public Exclusion exclusion() {
                return Exclusion.NONE;
            }

            @Override
            public void repository(RepositoryContext context) {
            }
        };
        try (MaintenanceScheduler scheduler = new MaintenanceScheduler(repositories, store,
                List.of(exclusive, shared), _ -> null, Duration.ofMinutes(10), null)) {
            Instant before = Instant.now();
            scheduler.runNow(NOW);
            assertThat(scheduler.nextDue().get("c9-exclusive"))
                    .as("the exclusive pass yields: not due again before one interval after it finished")
                    .isAfterOrEqualTo(before.plus(interval).minusSeconds(1));
            assertThat(scheduler.nextDue().get("c9-shared"))
                    .as("a pass no peer waits on keeps the schedule it had")
                    .isNull();
        }
    }

    // --- C8: runNow never doubles the scheduled pass --------------------------------------------------------------

    @Test
    void c8_an_admin_run_overlapping_an_in_flight_exclusive_pass_on_the_same_node_is_refused() throws Exception {
        new StoreRepositoryInventory(repositories.store("default", "alpha")).record("Maven", "org:lib", "1.0", NOW);
        AtomicInteger units = new AtomicInteger();
        MeterRegistry registry = new SimpleMeterRegistry();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (MaintenanceScheduler scheduler = new MaintenanceScheduler(repositories, store,
                List.of(counting("c8", units)), key -> null, Duration.ofMinutes(10), registry)) {
            Thread worker = new Thread(() -> {
                try {
                    scheduler.exclusively("c8", NOW, () -> {
                        holding.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException _) {
                            Thread.currentThread().interrupt();
                        }
                        return "held";
                    });
                } catch (IOException | RuntimeException e) {
                    throw new AssertionError(e);
                }
            }, "c8-worker");
            worker.start();
            assertThat(holding.await(10, TimeUnit.SECONDS)).as("the worker holds the lease").isTrue();

            scheduler.runNow(NOW);   // the admin run, on the SAME node, while the lease is live

            assertThat(units.get())
                    .as("Lease.acquire refuses a live lease regardless of holder - including this node's own - so an "
                            + "admin run can never double the in-flight scheduled pass")
                    .isZero();
            assertThat(registry.find("jenreg.maintenance.failures").counter())
                    .as("and the refused admin run is an INFO skip, never a counted failure").isNull();
            release.countDown();
            worker.join(10_000L);
        }
    }

    // --- C9: a degenerate lease ttl is refused at construction, naming its dial ------------------------------------

    @Test
    void c9_a_degenerate_lease_ttl_is_refused_at_construction_naming_cleanup_lease() {
        // BEFORE cleanup-lease=PT0S was accepted. It made every acquire immediately steal-able (so there was
        // no single-writer exclusion at all) AND made renewInterval = ttl/2 = 0, so scheduleAtFixedRate threw
        // IllegalArgumentException on every exclusive pass - which the worker loop then counted as a task failure.
        // One operator-reachable DURATION setting, two silent degradations, and no message naming the dial.
        for (Duration degenerate : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMillis(1))) {
            assertThatThrownBy(() -> new MaintenanceScheduler(repositories, store, List.of(idle("c9")),
                    key -> null, degenerate, null))
                    .as("a %s maintenance lease must be refused where it is configured", degenerate)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cleanup-lease");
        }
        assertThatThrownBy(() -> new MaintenanceScheduler(repositories, store, List.of(idle("c9")),
                key -> null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cleanup-lease");
    }

    @Test
    void c9_a_usable_lease_ttl_still_builds_and_sweeps() throws IOException {
        // The other half of the refusal: the guard must reject only the degenerate values, not a short-but-usable one.
        new StoreRepositoryInventory(repositories.store("default", "alpha")).record("Maven", "org:lib", "1.0", NOW);
        AtomicInteger units = new AtomicInteger();
        MeterRegistry registry = new SimpleMeterRegistry();
        try (MaintenanceScheduler scheduler = new MaintenanceScheduler(repositories, store,
                List.of(counting("c9b", units)), key -> null, Duration.ofMillis(100), registry)) {
            scheduler.runNow(NOW);
        }
        assertThat(units.get()).as("a 100ms lease is short but usable, and the pass runs").isEqualTo(1);
        assertThat(registry.find("jenreg.maintenance.failures").counter())
                .as("no scheduleAtFixedRate failure was counted").isNull();
    }

    // --- C10: the lease object is locks/<task-name> at the deployment root -----------------------------------------

    @Test
    void c10_a_pass_locks_on_locks_slash_its_own_task_name_at_the_deployment_root() throws IOException {
        // The lease object is part of the deployment's wire contract: a mixed-version fleet in which one version
        // renamed the task (or moved the object) has two live sweepers over one store and no way to notice.
        assertThat(LeaseGuard.object("cleanup")).isEqualTo(Scopes.space(Scopes.LOCKS) + "/cleanup");

        AtomicBoolean lockedWhileRunning = new AtomicBoolean();
        try (MaintenanceScheduler scheduler = new MaintenanceScheduler(repositories, store,
                List.of(idle("cleanup")), key -> null, Duration.ofMinutes(10), null)) {
            scheduler.exclusively("cleanup", NOW, () -> {
                lockedWhileRunning.set(store.exists(Scopes.space(Scopes.LOCKS) + "/cleanup"));
                return "ran";
            });
        }
        assertThat(lockedWhileRunning).as("the running pass holds locks/cleanup at the deployment root").isTrue();

        // C7, through the scheduler: a refused pass must leave the rival's lock untouched rather than releasing it.
        Lease rival = new Lease(store, Duration.ofMinutes(10));
        assertThat(rival.acquire("cleanup", "rival-node", NOW)).isTrue();
        try (MaintenanceScheduler scheduler = new MaintenanceScheduler(repositories, store,
                List.of(idle("cleanup")), key -> null, Duration.ofMinutes(10), null)) {
            scheduler.runNow(NOW);
        }
        assertThat(rival.renew("cleanup", "rival-node", NOW.plusSeconds(1)))
                .as("the refused node neither stole nor released the rival's lock").isTrue();
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /** How many maintenance worker loops exist right now, by exact thread name. */
    private static int workerLoops() {
        int loops = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (WORKER.equals(thread.getName()) && thread.isAlive()) {
                loops++;
            }
        }
        return loops;
    }

    /** Block a pass body until the test releases it, so the pass's life is the test's decision rather than a clock's.
     *  Bounded generously and loud on expiry: a pass that ended on its own would pull the lease out from under the
     *  probing that is meant to be happening inside it. */
    private static void hold(CountDownLatch until) {
        try {
            if (!until.await(60, TimeUnit.SECONDS)) {
                throw new AssertionError("the test never released the pass it was holding open");
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    /** A task that does nothing per repository, on an interval far beyond any test's lifetime. */
    private static MaintenanceTask idle(String name) {
        return task(name, _ -> {
        });
    }

    /** A task that counts the repository units it is handed. */
    private static MaintenanceTask counting(String name, AtomicInteger units) {
        return task(name, _ -> units.incrementAndGet());
    }

    private static MaintenanceTask task(String name, Unit unit) {
        return new MaintenanceTask() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Duration interval() {
                return Duration.ofHours(1);
            }

            @Override
            public void repository(RepositoryContext context) throws IOException {
                unit.run(context);
            }
        };
    }

    private interface Unit {

        void run(RepositoryContext context) throws IOException;
    }
}
