package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.cleanup.RetentionProvider;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.MaintenanceScheduler;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.RepositoryProperties;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.server.kernel.TaskSchedule;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.maintenance.TenantContext;
import build.jenesis.repository.staging.StagingProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>What the maintenance scheduler promises a task, driven by a hostile one.</b>
 *
 * <p>This suite exists because of a gap the ticket exposed rather than because of the ticket. {@code test/maint}'s
 * {@code MaintenanceTaskContract} is a contract kit in the plan's category 2, and it asserts, thoroughly, that each
 * of the fourteen discovered <em>tasks</em> honours the {@code MaintenanceTask} contract - driven through the real
 * scheduler, with its own falsification mutants. <b>Nothing asserted what the scheduler promises in return.</b> The
 * kit tests the guest; was in the host. So a defect that stopped every sweep, drain and garbage collection on a
 * node sat behind a green contract kit, and would have kept sitting there: no fixture can express "the loop I am
 * being driven by is still alive", because a fixture is only ever asked about itself.
 *
 * <p>The five promises are stated on {@code MaintenanceScheduler} itself and driven one per leg here. The subject is
 * always a deliberately hostile task - one that raises an {@link Error}, one whose {@code name()} throws after the
 * first call, one whose {@code interval()} throws - because a host contract is only meaningful against a guest that
 * breaks its side.
 *
 * <h2>Where the rethrow question landed, and why it is not verbatim</h2>
 * ruled that an {@code Error} out of an {@code EventSink} is attributed and <em>rethrown</em>: it is the runtime
 * or the module graph giving way, not a notification failing to queue, and it must not be filed as the subject's
 * answer. The ruling transfers, but the escalation does not, and the difference is which thread the {@code Error} is
 * on. {@code emit} runs on the producer's thread, so rethrowing hands the failure to a caller who can fail the
 * publish. The worker loop has <em>no</em> caller: "rethrow" there means letting the {@code Error} out of
 * {@code Thread.run()}, which kills the deployment's only maintenance loop, reports itself as one stack trace on
 * stderr from the default uncaught-exception handler, and counts nothing. That is less visible than the ERROR line it
 * replaces, not more - so the loop keeps running and the escalation goes to the operator, through the log, the
 * failure counter and the task's reported status. {@link MaintenanceScheduler#runNow(Instant)} is the one entry point
 * with a caller and it keeps the earlier rethrow exactly. Both directions are pinned below, because the difference is a
 * decision rather than an accident.
 *
 * <h2>Negative control, run against the real tree</h2>
 * Two plants, both reverted:
 * <ul>
 *   <li>the pre-containment restored ({@code contain} rethrowing every {@code Error}) made
 *       {@link #an_error_raised_on_the_worker_thread_does_not_end_the_loop()} fail with the defect verbatim -
 *       {@code Worker[enabled=true, alive=false, iterations=1, stopped=terminated:
 *       java.lang.NoClassDefFoundError]}, with the sibling task never having run; and</li>
 *   <li>{@code tasks()} re-entering the task to ask its name (the shape the old handler had, twice per catch block)
 *       made {@link #a_task_whose_name_throws_after_resolution_cannot_defeat_its_own_containment()} fail with the
 *       planted {@code name()} throwing straight out of {@code MaintenanceScheduler.tasks}.</li>
 * </ul>
 * Reverting both restored green, so neither leg passes because it asserts nothing.
 */
class SchedulerHostContractTest {

    private static final Instant NOW = Instant.parse("2026-06-27T00:00:00Z");

    /** How many times a wait re-reads the scheduler's recorded state before giving up. Attempts, never a deadline: a
     *  loaded machine makes the wait longer, not weaker, and running out fails naming what it wanted. */
    private static final int ATTEMPTS = 2_000;

    /** The Error the hostile tasks raise. {@link NoClassDefFoundError} on purpose: a plugin module whose optional
     *  dependency is absent is by far the likeliest way a real deployment meets one here, and it is exactly the case
     *  where "the module graph gave way" is true of <em>one task</em> and of nothing else on the node. */
    private static Error planted() {
        return new NoClassDefFoundError("planted: a maintenance plugin's class is missing from the module graph");
    }

    @TempDir
    Path root;

    private ArtifactStore store;
    private Repositories repositories;
    private MeterRegistry registry;
    private final List<MaintenanceScheduler> opened = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        RepositoryProperties properties = new RepositoryProperties();
        properties.setProxyEnabled(false);
        LiveConfig live = new LiveConfig(new Settings(store), properties, AdvisorySource.none(), _ -> null);
        repositories = new Repositories(store, Authorization.anonymous(), live,
                StagingProvider.resolve(_ -> null), RetentionProvider.resolve(_ -> null));
        registry = new SimpleMeterRegistry();
        // One repository, so every pass has a real (tenant, repository) unit to fan out over.
        new StoreRepositoryInventory(repositories.store("default", "alpha")).record("Maven", "org.a:lib", "1.0", NOW);
    }

    @AfterEach
    void closeSchedulers() {
        opened.forEach(MaintenanceScheduler::close);
    }

    // --- promise 1: no task can end the worker ----------------------------------------------------------------------

    @Test
    void an_error_raised_on_the_worker_thread_does_not_end_the_loop() {
        // exclusion() is called by the loop itself, OUTSIDE any unit - so an Error from here is the loop-level escape
        // names, not a fan-out one. Before the fix it left the bare jenesis-repository-maintenance thread and
        // every sweep, drain and GC on the node stopped until a settings refresh or a restart.
        AtomicInteger sane = new AtomicInteger();
        MaintenanceScheduler scheduler = scheduler(
                new Hostile("hostile") {
                    @Override
                    public Exclusion exclusion() {
                        throw planted();
                    }
                },
                new Hostile("sane") {
                    @Override
                    public void repository(RepositoryContext context) {
                        sane.incrementAndGet();
                    }
                });

        scheduler.start();
        await(() -> sane.get() > 0 && failures(scheduler, "hostile") > 0,
                "the sane task to run and the hostile one's Error to be counted", scheduler);

        assertThat(scheduler.alive()).as("the worker thread survived the Error").isTrue();
        assertThat(scheduler.worker().stopped()).as("and does not report itself stopped").isNull();
        assertThat(sane.get()).as("the OTHER task ran - containment is per task, not per iteration").isPositive();
        assertThat(failures(scheduler, "hostile")).as("nothing was swallowed: the Error is counted for its task")
                .isPositive();
        assertThat(counter("hostile")).as("and on jenreg.maintenance.failures{task=hostile}").isPositive();

        long iterations = scheduler.worker().iterations();
        await(() -> scheduler.worker().iterations() > iterations,
                "the loop to complete a further iteration after the Error", scheduler);
    }

    @Test
    void an_error_from_a_fanned_out_unit_is_counted_rather_than_lost_in_an_execution_exception() {
        // A unit runs on a pool thread, where an Error used to be captured by the FutureTask and re-surface as the
        // ExecutionException runUnits discarded without a word - the one path where a broken runtime produced no
        // diagnostic at all.
        MaintenanceScheduler scheduler = scheduler(new Hostile("units") {
            @Override
            public void repository(RepositoryContext context) {
                throw planted();
            }
        });

        scheduler.runNow(NOW);

        assertThat(failures(scheduler, "units")).as("the unit's Error is attributed to its task and counted")
                .isPositive();
        assertThat(counter("units")).isPositive();
    }

    // --- promise 4: a handler never re-enters the task it is reporting -----------------------------------------------

    @Test
    void a_task_whose_name_throws_after_resolution_cannot_defeat_its_own_containment() {
        // The old handler called task.name() TWICE inside its own catch block (once to log, once to count), so a task
        // that had stopped being able to name itself turned a contained pass failure into an escape. The name is now
        // read once, when the list is resolved, and every later use reads that.
        Poisoned poisoned = new Poisoned("poison");
        MaintenanceScheduler scheduler = scheduler(poisoned);

        assertThat(poisoned.names.get()).as("the scheduler asked the task its name exactly once, at resolution")
                .isEqualTo(1);
        assertThat(scheduler.tasks()).as("and every later read answers from that capture").containsExactly("poison");

        scheduler.runNow(NOW);

        assertThat(failures(scheduler, "poison"))
                .as("the pass failure is contained and counted under the captured name, with the task never asked "
                        + "again - asking it would have thrown out of the handler that exists to contain it")
                .isPositive();
        assertThat(poisoned.names.get()).as("still exactly one name() call over a whole pass").isEqualTo(1);
    }

    @Test
    void a_task_that_cannot_be_named_or_timed_is_refused_at_resolution_naming_its_class() {
        assertThatThrownBy(() -> scheduler(new Hostile("unnamed") {
            @Override
            public String name() {
                throw new IllegalStateException("planted: this task cannot say what it is called");
            }
        }))
                .as("a task that cannot be named can neither take its locks/<name> lease nor be reported, so it is "
                        + "refused where the failure can still be attributed to a class")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SchedulerHostContractTest")
                .hasMessageContaining("name()");

        assertThatThrownBy(() -> scheduler(new Hostile("untimed") {
            @Override
            public Duration interval() {
                throw planted();
            }
        }))
                .as("the cadence is read at resolution for the same reason: TaskSchedule runs outside the per-task "
                        + "containment, so an interval() that throws from in there would take the loop down")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("interval()");
    }

    // --- promise 3: nothing is swallowed, and where an Error goes depends on whether there is a caller ---------------

    @Test
    void an_error_on_an_on_demand_run_reaches_the_caller_and_is_counted_first() {
        // The one entry point with a caller keeps the earlier rethrow verbatim: an admin or a test asked for this pass on
        // its own thread, so it must not be told the pass completed when the runtime gave way underneath it.
        MaintenanceScheduler scheduler = scheduler(new Hostile("on-demand") {
            @Override
            public Exclusion exclusion() {
                throw planted();
            }
        });

        assertThatThrownBy(() -> scheduler.runNow(NOW))
                .as("runNow has a caller, so the Error is escalated to it rather than filed as the pass's answer")
                .isInstanceOf(NoClassDefFoundError.class)
                .hasMessageContaining("planted");
        assertThat(failures(scheduler, "on-demand"))
                .as("and it was attributed and counted BEFORE it propagated, so the report survives the rethrow")
                .isPositive();
    }

    @Test
    void an_ordinary_failure_is_contained_on_both_paths() {
        // The Error split must not have changed what happens to an ordinary failure: it is a unit of work failing,
        // contained and counted on every path, including a checked exception smuggled past the throws clause.
        MaintenanceScheduler scheduler = scheduler(new Hostile("ordinary") {
            @Override
            public Exclusion exclusion() {
                throw new IllegalStateException("planted: an ordinary failure");
            }
        });

        scheduler.runNow(NOW);
        assertThat(failures(scheduler, "ordinary")).as("contained on the caller path too, and counted").isPositive();
    }

    // --- promise 5: the worker says whether it is running -------------------------------------------------------------

    @Test
    void the_worker_reports_liveness_while_it_has_nothing_to_do() {
        // the earlier class, applied to the loop: a signal that only moves when there is work cannot distinguish a
        // deployment with nothing due from one whose worker has stopped. The iteration stamp advances on every pass
        // round the loop, so an idle deployment reads as running.
        MaintenanceScheduler scheduler = scheduler(new Hostile("quiet") {
            @Override
            public Duration interval() {
                return Duration.ofDays(1);      // never due during this test
            }
        });

        assertThat(scheduler.worker().alive()).isFalse();
        assertThat(scheduler.worker().stopped()).as("a scheduler that was never started says so").isEqualTo("not started");

        scheduler.start();
        await(() -> scheduler.worker().lastIteration() != null, "the worker's first scheduling iteration", scheduler);

        MaintenanceScheduler.Worker running = scheduler.worker();
        assertThat(running.alive()).isTrue();
        assertThat(running.stopped()).isNull();
        assertThat(running.iterations()).as("an idle iteration is still an iteration").isPositive();
        assertThat(scheduler.taskRuns().get("quiet"))
                .as("...and no pass has run, which is the pair of readings that used to be indistinguishable")
                .isNull();

        scheduler.close();
        assertThat(scheduler.worker().alive()).isFalse();
        assertThat(scheduler.worker().stopped()).as("and an ended loop records why").isEqualTo("closed");
    }

    // --- helpers ------------------------------------------------------------------------------------------------------

    private MaintenanceScheduler scheduler(MaintenanceTask... tasks) {
        MaintenanceScheduler scheduler = new MaintenanceScheduler(repositories, store, List.of(tasks), key -> null,
                Duration.ofMinutes(10), registry, 1);
        opened.add(scheduler);
        return scheduler;
    }

    private static long failures(MaintenanceScheduler scheduler, String task) {
        TaskSchedule.TaskRun run = scheduler.taskRuns().get(task);
        return run == null ? 0L : run.failures();
    }

    private double counter(String task) {
        Counter counter = registry.find("jenreg.maintenance.failures").tag("task", task).counter();
        return counter == null ? 0d : counter.count();
    }

    private static void await(BooleanSupplier condition, String what, MaintenanceScheduler scheduler) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for " + what, interrupted);
            }
        }
        throw new AssertionError("timed out waiting for " + what + "; worker=" + scheduler.worker()
                + " runs=" + scheduler.taskRuns());
    }

    /** A well-behaved pass by default, so each leg overrides exactly the one method it is hostile in. Non-exclusive,
     *  so {@code runNow} drives it without a lease, and due on a short cadence so the worker picks it up promptly. */
    private static class Hostile implements MaintenanceTask {

        private final String name;

        private Hostile(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Duration interval() {
            return Duration.ofMillis(1);
        }

        @Override
        public Exclusion exclusion() {
            return Exclusion.NONE;
        }

        @Override
        public void repository(RepositoryContext context) throws IOException {
        }

        @Override
        public void tenant(TenantContext context) throws IOException {
        }
    }

    /** A task that answers its name once and then cannot: the shape that used to defeat the containment from inside
     *  its own handler, because the handler asked the broken task what to blame. */
    private static final class Poisoned extends Hostile {

        private final AtomicInteger names = new AtomicInteger();

        private Poisoned(String name) {
            super(name);
        }

        @Override
        public String name() {
            if (names.incrementAndGet() > 1) {
                throw new IllegalStateException("planted: this task can no longer say what it is called");
            }
            return super.name();
        }

        @Override
        public void repository(RepositoryContext context) throws IOException {
            throw new IOException("planted: the pass fails, and its handler must not need to ask my name");
        }
    }
}
