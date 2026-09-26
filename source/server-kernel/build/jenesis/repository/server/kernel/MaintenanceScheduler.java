package build.jenesis.repository.server.kernel;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.maintenance.UnitFailures;
import build.jenesis.repository.maintenance.TenantContext;
import build.jenesis.repository.server.NodeFingerprintPublisher;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Requests;
import build.jenesis.repository.store.RunningMarker;
import build.jenesis.repository.store.Lease;
import build.jenesis.repository.walk.BoundedChildren;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The one neutral scheduler for the {@code ServiceLoader}-discovered background passes (retention sweep,
 * vulnerability re-scan, ...): it owns the worker thread - started and stopped through Spring's bean lifecycle so
 * {@link #close} interrupts and joins it and {@link #alive} lets a health indicator watch it - and the tenant and
 * repository iteration. Each task runs on its own interval; with no task installed and enabled, the worker stays idle
 * and the deployment simply has no background maintenance. {@link #runNow} is public so a pass can be driven
 * synchronously - by a test, or by an admin - without the thread; an exclusive pass still takes (and promptly releases)
 * its single-writer lease, so an on-demand run never sweeps concurrently with the scheduled pass on another node. A
 * failing pass or unit is logged and counted ({@code jenreg.maintenance.failures}) rather than swallowed,
 * and the worker stays alive across it.
 *
 * <p>The scheduler is split <strong>in place</strong> into three collaborators inside this module, rather than
 * wrapped or duplicated by a second scheduler: {@link TaskSchedule} owns the due-time
 * arithmetic and the per-task run bookkeeping, {@link LeaseGuard} owns the single-writer {@link Lease} a
 * {@link MaintenanceTask.Exclusion#LEASE lease-owned} pass locks on (keyed by the task's name, so the retention pass
 * keeps the {@code locks/cleanup} object a mixed-version fleet expects), and {@link PassMetrics} owns the Micrometer
 * sink.
 * The worker thread, the tenant/repository iteration and this public surface stay here: there is still exactly
 * <em>one</em> scheduler, one lease owner and one iteration owner in the deployment.
 *
 * <p>The enabled task list is re-resolved on {@link #refresh()}, which the settings-convergence pass calls when the
 * stored settings changed: a background pass (cleanup, scan, dependents) toggled on or off through the modules console
 * takes effect on the worker's next iteration without a restart, since each pass reads its own enablement through the
 * live {@code config} lookup. A pass that is disabled simply drops out of the next iteration; one enabled after boot
 * starts the worker if it was idle. The single-writer {@link Lease} semantics are unchanged - each pass still locks on
 * its own name. Tasks that own a client or a thread of their own (the trackers, the audit trail) are not scheduled
 * here and stay restart-bound.
 *
 * <p>The boot list and the re-resolve are <strong>two constructor parameters</strong>, not one supplier called twice,
 * because a provider's construction failure means different things in the two phases: at boot it is fatal (the
 * deployment refuses to start half a task list it may never be able to complete), while on a convergence tick over a
 * server that is already serving it is contained per provider. The scheduler itself takes no position on that - it
 * calls what it was handed - but it cannot conflate the two, which is exactly what a single supplier did.
 *
 * <h2>What this scheduler promises a task, and what it promises the deployment</h2>
 * The {@code MaintenanceTask} contract says what a <em>pass</em> owes; these are the promises the <em>host</em> makes
 * in return, and {@code SchedulerHostContractTest} drives each of them with a deliberately hostile task:
 * <ol>
 *   <li><b>No task can end the worker.</b> Everything a discovered task does on the worker thread - {@code name()},
 *       {@code interval()}, {@code exclusion()}, {@code repository}, {@code tenant}, {@code completed} - runs inside
 *       {@link #contain}, including anything it raises that is not an exception at all.</li>
 *   <li><b>One task's failure never stops another's.</b> Containment is per task, per tenant and per fanned-out unit,
 *       and the remaining tasks of the same iteration still run.</li>
 *   <li><b>Nothing is swallowed.</b> Every contained failure is named at {@code WARNING} (an {@link Error} at
 *       {@code ERROR}) and counted on {@code jenreg.maintenance.failures}, and the task reports
 *       {@code FAILED} through the observability seam. See {@link Escalation} for where an {@code Error} goes and why
 *       the answer differs between the worker loop and {@link #runNow(Instant)}.</li>
 *   <li><b>A handler never re-enters the task it is reporting.</b> Names come from {@link ScheduledTask}, captured at
 *       resolution - the old handler called {@code task.name()} twice inside its own catch block, so a throwing
 *       {@code name()} defeated the containment from inside the very task being contained.</li>
 *   <li><b>The worker says whether it is running.</b> {@link #worker()} stamps every completed scheduling iteration,
 *       due work or not, and records why the loop stopped if it ever does - so "maintenance is not running" and
 *       "maintenance found nothing to do" are different readings rather than the same silence.</li>
 * </ol>
 */
public final class MaintenanceScheduler implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(MaintenanceScheduler.class);

    /** The default fan-out width: one worker per core, floored at two so a single-core host still parallelises, and
     *  capped so a large host does not point an unbounded thread count at the store. */
    private static final int DEFAULT_WORKERS =
            Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));

    /** The largest number of (tenant, repository) units the fan-out submits and awaits at once. A fleet with far more
     *  repositories than this never holds one {@link Callable} (and the matching {@link Future}) per unit in heap:
     *  the units are drained a batch at a time. Sized to keep the worker pool fed - many units per worker - while
     *  bounding the pass's transient footprint to O(batch) regardless of how large the fleet grows. */
    private static final int FANOUT_BATCH = 1024;

    /** How long after a failed pass its retry request falls due. */
    static final Duration RETRY = Duration.ofHours(1);

    private final Repositories repositories;
    private final ArtifactStore root;
    private final Supplier<MaintenanceTaskProvider.Contained> resolver;
    /** The enabled passes, each paired with the name and cadence read off it <em>once</em> when this list was
     *  resolved ({@link ScheduledTask}). Nothing downstream re-enters a task to ask what it is called, which is what
     *  makes the containment below unbreakable from inside a broken task's own handler. */
    private volatile List<ScheduledTask> tasks;
    private final UnaryOperator<String> config;
    /** This node, for its running marker - the one derivation, so the fingerprint agrees. */
    private final String nodeId;
    /** The per-(tenant) configuration lookup a running pass reads through {@link PassRepositoryContext#config()} and
     *  {@link PassTenantContext#config()}: the same pin &gt; tenant-override &gt; global &gt; default chain as
     *  {@link #config}, but resolved for the pass's own tenant so a tenant-overridable setting (a webhook endpoint, a
     *  gate policy, a retention age over a tenant's own telemetry space) actually takes effect in the sweep - a
     *  global-only key resolves deployment-wide exactly as before, so this is a no-op for every deployment knob. Both
     *  hooks resolve through it; the deployment-global {@link #config()} accessor (the on-demand endpoints'
     *  provider lookup) stays tenant-agnostic. */
    private final BiFunction<String, String, String> tenantConfig;
    /** The single-writer guard (R7): the one owner of {@code locks/<task>} for the whole deployment. */
    private final LeaseGuard leases;
    /** The due-time and run bookkeeping (R4, R8) - storeless, threadless, driven by the one worker loop below. */
    private final TaskSchedule schedule = new TaskSchedule();
    /** The Micrometer sink (R9, R10) - the only meter-aware collaborator. */
    private final PassMetrics metrics;
    /** The bounded pool the (tenant, repository) units of a pass fan out across, so a large fleet's sweep is not one
     *  serial walk; daemon-threaded so a test that never closes the scheduler still lets its JVM exit. */
    private final ExecutorService workers;
    private volatile boolean running;
    private Thread thread;
    /** When the worker last <em>completed</em> a scheduling iteration - stamped on every pass round the loop, whether
     *  or not anything was due, so an idle deployment reads as running rather than as never having run (a
     *  liveness signal that stops instead of reading zero cannot distinguish "nothing to do" from "not running"). */
    private volatile Instant lastIteration;
    /** How many scheduling iterations the worker has completed - a monotone counter an operator can watch advance
     *  without doing clock arithmetic on {@link #lastIteration}. */
    private final AtomicLong iterations = new AtomicLong();
    /** Why the worker loop is no longer running, or {@code null} while it is: {@code closed} for the ordinary bean
     *  shutdown, and a named termination otherwise. Reported through {@link #worker()} so a stopped loop is a state
     *  the health endpoint and the observability surface can read, rather than one stack trace on stderr from the
     *  default uncaught-exception handler. */
    private volatile String stopped = "not started";

    /** The passes the last {@link #refresh()} could not build, by name with a one-line cause - reported as failed by
     *  this scheduler's observability rather than left to vanish from a shorter task list. */
    private volatile Map<String, String> unavailable = Map.of();

    /** The passes the last {@link #refresh()} could not build, by name with a one-line cause. */
    public Map<String, String> unavailable() {
        return unavailable;
    }

    /** A fixed task list (a test or a single-shot pass); the list never re-resolves, so {@link #refresh()} is a no-op. */
    public MaintenanceScheduler(Repositories repositories, ArtifactStore root, List<MaintenanceTask> tasks,
                                UnaryOperator<String> config, Duration leaseTtl, MeterRegistry registry) {
        this(repositories, root, tasks, () -> MaintenanceTaskProvider.Contained.of(tasks), config, (tenant, key) -> config.apply(key),
                leaseTtl, registry, DEFAULT_WORKERS);
    }

    /** A fixed task list at an explicit fan-out width - a test uses this to drive a known pool size and observe the
     *  parallel processing of a repository set. */
    public MaintenanceScheduler(Repositories repositories, ArtifactStore root, List<MaintenanceTask> tasks,
                                UnaryOperator<String> config, Duration leaseTtl, MeterRegistry registry, int workers) {
        this(repositories, root, tasks, () -> MaintenanceTaskProvider.Contained.of(tasks), config, (tenant, key) -> config.apply(key),
                leaseTtl, registry, workers);
    }

    /** A fixed task list whose per-pass reads resolve tenant-scoped, at an explicit fan-out width - the deployment's
     *  own resolution ({@code tenantConfig}) with the pool pinned, which is what the {@code MaintenanceTask} contract
     *  kit drives: it needs the fan-out one worker wide for its "crash after the first unit" point to name one definite
     *  unit, and it needs the real tenant chain to prove a tenant override reaches a running pass. */
    public MaintenanceScheduler(Repositories repositories, ArtifactStore root, List<MaintenanceTask> tasks,
                                UnaryOperator<String> config, BiFunction<String, String, String> tenantConfig,
                                Duration leaseTtl, MeterRegistry registry, int workers) {
        this(repositories, root, tasks, () -> MaintenanceTaskProvider.Contained.of(tasks), config, tenantConfig, leaseTtl, registry, workers);
    }

    /** A live task list: {@code booted} is the list this scheduler starts with - resolved by the <em>caller</em>, which
     *  is what lets a deployment resolve it strictly ({@code MaintenanceTaskProvider.resolve}) - while {@code resolver}
     *  re-runs on every {@link #refresh()}, so a pass toggled on or off in the stored settings converges here without a
     *  restart. The two are separate parameters on purpose: the boot list and the re-resolve are the same call in a
     *  test but must not be in a deployment, where boot is fatal on a provider failure and the re-resolve contains it
     *  ({@code MaintenanceTaskProvider.resolveContained}). The per-pass reads resolve through the same {@code config}
     *  (deployment-global), so a tenant override never takes effect - the eight-argument overload wires a
     *  tenant-scoped resolver for that. */
    public MaintenanceScheduler(Repositories repositories, ArtifactStore root, List<MaintenanceTask> booted,
                                Supplier<MaintenanceTaskProvider.Contained> resolver,
                                UnaryOperator<String> config, Duration leaseTtl, MeterRegistry registry) {
        this(repositories, root, booted, resolver, config, (tenant, key) -> config.apply(key),
                leaseTtl, registry, DEFAULT_WORKERS);
    }

    /** A live task list whose <em>per-pass</em> reads resolve tenant-scoped: {@code config} stays the deployment-global
     *  lookup (task enablement, the on-demand endpoints' provider), while {@code tenantConfig} resolves a running pass's
     *  settings for its own tenant, so a tenant-overridable key (a per-tenant webhook endpoint, gate policy) actually
     *  takes effect in the sweep. A global-only key resolves deployment-wide through either, so the two agree on every
     *  deployment knob. This is the wiring the live deployment uses; the plain overloads keep the global-only behavior
     *  for tests that do not exercise per-tenant overrides. */
    public MaintenanceScheduler(Repositories repositories, ArtifactStore root, List<MaintenanceTask> booted,
                                Supplier<MaintenanceTaskProvider.Contained> resolver, UnaryOperator<String> config,
                                BiFunction<String, String, String> tenantConfig,
                                Duration leaseTtl, MeterRegistry registry) {
        this(repositories, root, booted, resolver, config, tenantConfig, leaseTtl, registry, DEFAULT_WORKERS);
    }

    private MaintenanceScheduler(Repositories repositories, ArtifactStore root, List<MaintenanceTask> booted,
                                 Supplier<MaintenanceTaskProvider.Contained> resolver, UnaryOperator<String> config,
                                 BiFunction<String, String, String> tenantConfig,
                                 Duration leaseTtl, MeterRegistry registry, int workers) {
        this.repositories = repositories;
        this.root = root;
        this.resolver = resolver;
        // Read every task's name and cadence here, once. At boot this is inside a @Bean method, so a task that cannot
        // supply either fails the context naming its class - the same strict phase MaintenanceTaskProvider.resolve is.
        this.tasks = scheduled(booted);
        this.config = config;
        this.tenantConfig = tenantConfig;
        this.nodeId = NodeFingerprintPublisher.nodeId(config);
        // A degenerate lease ttl is refused here, naming cleanup-lease: it removes single-writer exclusion outright and
        // makes every exclusive pass throw, so it must never resolve to a scheduler that looks healthy (§9).
        this.leases = new LeaseGuard(root, leaseTtl);
        this.metrics = new PassMetrics(registry);
        this.workers = Executors.newFixedThreadPool(Math.max(1, workers), runnable -> {
            Thread worker = new Thread(runnable, "jenesis-repository-maintenance-worker");
            worker.setDaemon(true);
            return worker;
        });
    }

    /** The effective configuration lookup the scheduled passes read (an operator's pin over the runtime settings
     *  over the deployment properties, {@code null} for an unset key) - shared with the on-demand maintenance
     *  endpoints so a provider they resolve (the garbage collector, the walk) reads exactly the settings its
     *  scheduled counterpart does. */
    public UnaryOperator<String> config() {
        return config;
    }

    /** Whether any maintenance task is installed and enabled. */
    public boolean enabled() {
        return !tasks.isEmpty();
    }

    /** Whether the worker thread is started and alive; an enabled scheduler whose thread has died is unhealthy. */
    public boolean alive() {
        return thread != null && thread.isAlive();
    }

    /** The names of the enabled tasks, for the health detail - the identifiers captured at resolution, so this read
     *  cannot be made to throw (or to answer differently) by a task that has since broken. */
    public List<String> tasks() {
        List<String> names = new ArrayList<>();
        for (ScheduledTask task : tasks) {
            names.add(task.name());
        }
        return names;
    }

    /**
     * The worker loop's own state - whether any pass is enabled, whether the thread is alive, when it last
     * <em>completed</em> a scheduling iteration, how many it has completed, and (once it is no longer running) why it
     * stopped.
     *
     * <p>This exists because "maintenance is not running" and "maintenance found nothing to do" were previously
     * indistinguishable from outside (the drain depth gauges' problem, on the loop): a task that
     * has never been due reports {@code UNKNOWN}/"has not completed a run yet" whether the worker is sweeping every
     * thirty seconds or died an hour ago, and the only signal that it died was one stack trace from the default
     * uncaught-exception handler. The iteration stamp advances on <em>every</em> pass round the loop, due work or not,
     * so it reads as liveness rather than as work.
     */
    public Worker worker() {
        // One read of the thread's liveness, not two: a record whose whole job is a consistent snapshot must not be
        // able to report alive together with a stop reason because the loop ended between two calls.
        boolean alive = alive();
        return new Worker(enabled(), alive, lastIteration, iterations.get(), alive ? null : stopped);
    }

    /** An immutable snapshot of the worker loop: whether a pass is {@code enabled}, whether the thread is
     *  {@code alive}, the instant it last completed a scheduling {@code iteration} (null before the first),
     *  how many {@code iterations} it has completed, and why it {@code stopped} (null while it is running). */
    public record Worker(boolean enabled, boolean alive, Instant lastIteration, long iterations, String stopped) {
    }

    /** An immutable snapshot of every task that has started at least one run on this node, keyed by task name - the
     *  read side the discovered {@code MaintenanceObservability} adapter reports each task's last-run / status through. */
    public Map<String, TaskSchedule.TaskRun> taskRuns() {
        return schedule.runs();
    }

    /**
     * Start the worker thread. <strong>Idempotent</strong>: a second call over a live worker is a no-op rather than a
     * second loop. This method once called {@code startWorker()} unconditionally while {@link #refresh()}
     * guarded, so a second {@code start()} overwrote the thread field while the old loop kept running against a
     * {@code running} flag that was still true - two worker loops on one node, each taking and releasing the same
     * leases.
     */
    public synchronized void start() {
        running = true;
        // Requests reach the root through this node; and a node that boots over its own running marker did not
        // shut down cleanly, which is the one case a walk of the store exists to repair - so it asks for one.
        Requests.installRoot(root);
        try {
            if (RunningMarker.boot(root, nodeId)) {
                Requests.request(root, Requests.WALK, "node " + nodeId + " did not shut down cleanly");
                LOGGER.warn("node {} did not shut down cleanly; a walk of the store is requested", nodeId);
            }
        } catch (IOException | RuntimeException unwritable) {
            LOGGER.warn("the running marker of node {} could not be written: {}", nodeId, unwritable.toString());
        }
        if (tasks.isEmpty()) {
            LOGGER.info("No maintenance task is enabled yet; the maintenance worker starts on the first enablement");
            return;
        }
        startWorker();
    }

    /** Re-resolve the enabled task list from the live configuration (the settings-convergence pass calls this on a
     *  stored-settings change), and start the worker if a pass was enabled after boot. A pass disabled here drops out of
     *  the worker's next iteration; the lease and interval bookkeeping are unchanged. A no-op for a fixed task list. */
    public synchronized void refresh() {
        // The re-read of every task's name and cadence happens here too, and it is deliberately allowed to throw: on
        // a convergence tick SettingsRefresh contains it and keeps the last resolved list whole, so a task that has
        // stopped being able to name itself neither replaces a working schedule nor takes a serving node down.
        MaintenanceTaskProvider.Contained resolved = resolver.get();
        this.tasks = scheduled(resolved.tasks());
        this.unavailable = resolved.unavailable();
        if (running && !tasks.isEmpty()) {
            startWorker();
        }
    }

    /** Pair every resolved task with the name and cadence read off it once, refusing one that cannot supply either
     *  (see {@link ScheduledTask}). This is the single point at which a {@link MaintenanceTask} becomes something this
     *  scheduler will drive - design gate 3's "extend the existing choke point" applied to identity. */
    private static List<ScheduledTask> scheduled(List<MaintenanceTask> resolved) {
        List<ScheduledTask> scheduled = new ArrayList<>(resolved.size());
        for (MaintenanceTask task : resolved) {
            scheduled.add(ScheduledTask.of(task));
        }
        return List.copyOf(scheduled);
    }

    /** Start the one worker loop, unless it is already running. The single guard both {@link #start()} and
     *  {@link #refresh()} go through, so the deployment can never hold two loops. */
    private void startWorker() {
        if (thread != null && thread.isAlive()) {
            return;
        }
        LOGGER.info("Scheduling maintenance tasks {}", tasks());
        thread = new Thread(this::loop, "jenesis-repository-maintenance");
        thread.start();
    }

    @Override
    public synchronized void close() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(10_000L);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
        workers.shutdownNow();
        leases.close();
        try {
            RunningMarker.clean(root, nodeId);
        } catch (IOException | RuntimeException unremovable) {
            LOGGER.warn("the running marker of node {} could not be removed; the next boot will ask for a walk: {}",
                    nodeId, unremovable.toString());
        }
    }

    /**
     * The one worker loop. Everything a discovered task does on this thread runs inside {@link #contain}, so no pass
     * can end it: the loop stops only when the bean is closed, or when the scheduler's <em>own</em> arithmetic breaks
     * - and that termination is recorded rather than left to the default uncaught-exception handler.
     */
    private void loop() {
        stopped = null;
        iterated();                     // the loop is up: an idle deployment reads as running, not as never having run
        try {
            while (running) {
                // A standing request runs its task now, whatever the clock says, and is cleared when the pass ran:
                // the reason a walk of the store needs no cadence to happen when a crash made it necessary. Looked
                // for before the sleep, so a request standing when the worker starts - an unclean boot's - runs
                // first, and a later one within one idle poll.
                Instant asked = Instant.now();
                for (Map.Entry<ScheduledTask, Requests.Request> requested : requested().entrySet()) {
                    ScheduledTask task = requested.getKey();
                    Requests.Request request = requested.getValue();
                    contain(task.name(), "on request (" + request.reason() + ")", Escalation.OPERATOR, () -> {
                        pass(task, asked);
                        Requests.clear(root, request.subject());
                    });
                }
                // Neither call reaches a task any more: TaskSchedule works off the name and cadence ScheduledTask
                // captured at resolution, so a hostile task cannot throw from out here, where there is nothing to
                // attribute a failure to and no pass to contain it in.
                Duration sleep = schedule.sleep(tasks, Instant.now());
                try {
                    Thread.sleep(sleep.toMillis());
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    stopped = running ? "interrupted" : "closed";
                    return;
                }
                if (!running) {
                    stopped = "closed";
                    return;
                }
                Instant at = Instant.now();
                for (ScheduledTask task : schedule.due(tasks, at)) {
                    contain(task.name(), "on its scheduled pass", Escalation.OPERATOR, () -> pass(task, at));
                }
                iterated();
            }
            stopped = "closed";
        } catch (Throwable fatal) {
            stopped = "terminated: " + fatal.getClass().getName();
            try {
                LOGGER.error("The maintenance worker thread is terminating - EVERY sweep, drain and GC on this node "
                        + "stops until a settings refresh or a restart. This is the scheduler's own arithmetic "
                        + "breaking, not a task failing: a task's failure is contained to that task.", fatal);
            } catch (Throwable diagnostic) {
                suppress(fatal, diagnostic);
            }
            throw fatal;
        }
    }

    /** One scheduled pass of a due task: under its single-writer lease when it is exclusive (a {@code false} return
     *  means another node owns it right now - normal in a fleet, nothing to do here), otherwise straight through. */
    private void pass(ScheduledTask task, Instant at) throws IOException {
        if (task.task().exclusion().lease()) {
            exclusivePass(task, at, Escalation.OPERATOR);
        } else {
            run(task, at, () -> false, Escalation.OPERATOR);
        }
    }

    /** The standing requests that name an enabled task: the walk's subject is the rebuild task, any other subject
     *  is a task's own name. A request for a task that is not enabled stands until it is, and is reported. */
    private Map<ScheduledTask, Requests.Request> requested() {
        Map<ScheduledTask, Requests.Request> matched = new LinkedHashMap<>();
        List<Requests.Request> pending;
        try {
            pending = Requests.pending(root);
        } catch (IOException | RuntimeException unreadable) {
            LOGGER.warn("the standing requests could not be read: {}", unreadable.toString());
            return matched;
        }
        Instant now = Instant.now();
        for (Requests.Request request : pending) {
            if (!request.due(now)) {
                continue;   // a retry that is not yet due stands, and is looked at again next tick
            }
            String name = request.subject().equals(Requests.WALK) ? "rebuild" : request.subject();
            for (ScheduledTask task : tasks) {
                if (task.name().equals(name)) {
                    matched.put(task, request);
                }
            }
        }
        return matched;
    }

    /** The standing requests, as the observability and admin surfaces show them. */
    public List<Requests.Request> requests() throws IOException {
        return Requests.pending(root);
    }

    /** Stamp a completed scheduling iteration - the worker's liveness signal ({@link #worker()}). */
    private void iterated() {
        lastIteration = Instant.now();
        iterations.incrementAndGet();
    }

    /** Run every enabled task once, synchronously - for a test or an admin-triggered pass. An exclusive task still
     *  takes its single-writer lease (skipped with a log line when a rival node - or this node's own in-flight pass -
     *  holds it, so an on-demand run never doubles the scheduled sweep) and releases it on completion, so back-to-back
     *  on-demand runs do not wait out the ttl.
     *
     *  <p>This is the one entry point with a <em>caller</em>, so it is also the one where an {@link Error} out of a
     *  task is rethrown ({@link Escalation#CALLER}) - see {@link #contain}. */
    public void runNow(Instant now) {
        for (ScheduledTask task : tasks) {
            contain(task.name(), "on an on-demand run", Escalation.CALLER, () -> {
                if (task.task().exclusion().lease()) {
                    if (!exclusivePass(task, now, Escalation.CALLER)) {
                        LOGGER.info("Skipping maintenance task '{}': its single-writer lease is held by another node",
                                task.name());
                    }
                } else {
                    run(task, now, () -> false, Escalation.CALLER);
                }
            });
        }
    }

    /**
     * Where an {@link Error} raised by a discovered task goes. The question was settled first for {@code EventSink} -
     * an {@code Error} is the runtime or the module graph giving way rather than a unit of work failing, so it is
     * attributed and <em>rethrown</em> rather than filed as the subject's answer - and the ruling transfers here with
     * one deliberate refinement: <b>an {@code Error} is escalated to whoever can act on it, and rethrowing is only an
     * escalation where there is a caller to receive it.</b>
     */
    private enum Escalation {

        /**
         * To the caller - {@link #runNow(Instant)}. An admin or a test asked for this pass on its own thread, so
         * the ruling applies verbatim: the {@code Error} propagates, the request fails loudly instead of reporting a
         * completed pass that did nothing, and the remaining tasks are starved exactly as {@code emit}'s later sinks
         * are. That trade was accepted there and is accepted here for the same reason.
         */
        CALLER,

        /**
         * To the operator - the worker loop, and every unit fanned out from it. There is no caller on those threads:
         * "rethrow" means letting the {@code Error} out of {@code Thread.run()}, which kills the deployment's
         * <em>only</em> maintenance loop, stops every other sweep, drain and GC until a settings {@code refresh()} or
         * a restart, counts nothing, and reports itself as one stack trace on stderr from the default
         * uncaught-exception handler. Measured against what the ruling actually wanted - the failure attributed rather
         * than swallowed, and visible to whoever can act - that is strictly worse on every axis: <em>less</em>
         * visible than an ERROR through the configured appenders, uncounted, and with a blast radius thirty passes
         * wide for what is most often one plugin module's {@code NoClassDefFoundError}.
         *
         * <p>So the loop survives, and the escalation is the one an operator can actually see: an ERROR naming the
         * task and saying the runtime gave way, a counted failure on
         * {@code jenreg.maintenance.failures{task=...}}, and the task reported {@code FAILED} through
         * the observability seam. Nothing is swallowed - what changes is who the report goes to. The pass keeps its
         * schedule rather than being withdrawn, deliberately: a {@code StackOverflowError} on one pathological tree
         * or an {@code OutOfMemoryError} under load is often transient, and permanently withdrawing (say) the garbage
         * collector because the JVM was briefly out of memory would <em>cause</em> the outage this ticket exists to
         * prevent. A pass that keeps breaking keeps saying so, every interval.
         */
        OPERATOR
    }

    /** A body that runs a discovered task's own code. */
    @FunctionalInterface
    private interface Attempt {

        void run() throws IOException;
    }

    /**
     * Run {@code attempt} on behalf of {@code task}, containing its failure to that task. {@code task} is the name
     * captured at resolution, never a fresh {@code name()} call - the handler must not have to re-enter a broken task
     * to find out what to blame, which is precisely how the old handler could be defeated from inside the very task
     * it was reporting (it called {@code name()} twice, once to log and once to count).
     *
     * <p>An ordinary failure - a checked exception, a {@link RuntimeException}, one smuggled past a {@code throws}
     * clause - is a unit of work failing: contained, named at {@code WARNING} and counted. An {@link Error} is
     * attributed at {@code ERROR} and counted, then goes where {@code escalate} says. The diagnostic and the count
     * are each guarded and suppressed into the original failure, because a report must never be able to replace what
     * it reports.
     */
    private void contain(String task, String where, Escalation escalate, Attempt attempt) {
        try {
            attempt.run();
        } catch (Error broken) {
            report(task, where, broken, true);
            if (escalate == Escalation.CALLER) {
                throw broken;
            }
        } catch (Throwable failure) {
            report(task, where, failure, false);
        }
    }

    /** Log and count one task's failure, so that neither step can replace the failure it describes. */
    private void report(String task, String where, Throwable failure, boolean broken) {
        try {
            if (broken) {
                LOGGER.error("Maintenance task '" + task + "' raised an Error " + where + " - the runtime or the "
                        + "module graph gave way rather than a unit of work failing. The pass is counted as failed "
                        + "and the maintenance worker keeps running: it is this deployment's only sweep, drain and "
                        + "GC loop, so ending it here would stop every other pass as well.", failure);
            } else {
                LOGGER.warn("Maintenance task '" + task + "' failed " + where + " - contained to this task; every "
                        + "other maintenance pass is unaffected.", failure);
            }
        } catch (Throwable diagnostic) {
            suppress(failure, diagnostic);
        }
        try {
            failure(task);
        } catch (Throwable counting) {
            suppress(failure, counting);
        }
    }

    /** Attach a failed diagnostic to the failure it was reporting, never letting the attachment itself escape. */
    private static void suppress(Throwable failure, Throwable diagnostic) {
        if (failure == diagnostic) {
            return;
        }
        try {
            failure.addSuppressed(diagnostic);
        } catch (Throwable _) {
            // nothing further is worth trying on a runtime this broken; the original still reaches its handler
        }
    }

    /**
     * One scheduled or on-demand exclusive pass: run under the task's single-writer lease, and - the fix -
     * count the pass as FAILED when the lease was lost mid-pass. A refused <em>acquisition</em> (this returns
     * {@code false}) stays a normal, uncounted skip: in a fleet exactly one node runs the pass and the others skip. But
     * a lease this node <em>held</em> and then lost means it stalled past its own ttl and a rival legitimately took
     * over, so two sweepers were live over one store - which was previously logged only, leaving the dashboard showing
     * a clean sweep. The fan-out additionally stops submitting further batches the moment that happens; see
     * {@link LeaseGuard}.
     */
    private boolean exclusivePass(ScheduledTask task, Instant now, Escalation escalate) throws IOException {
        return leases.exclusively(task.name(), now, holding -> {
            try {
                run(task, now, holding::lost, escalate);
            } finally {
                // Hand the lease round: this node is not due again for one interval after the pass ended, so a peer
                // polling at the same cadence finds the lease free once, whatever the pass took (TaskSchedule.yield).
                schedule.yield(task.name(), Instant.now().plus(task.interval()));
            }
            if (holding.lost()) {
                failure(task.name());
            }
            return Boolean.TRUE;
        }).isPresent();
    }

    /** When each task is next due on this node - the schedule's own word, which a pass just run has moved. */
    public Map<String, Instant> nextDue() {
        return schedule.nextDue();
    }

    /**
     * Run {@code body} while holding the named single-writer maintenance lease: acquired up front (an empty return -
     * and no run - when another holder's lease is live), renewed on a wall-clock cadence shorter than the ttl while
     * the body runs (so a single long unit never lets the lease lapse mid-pass and hand a rival a second, concurrent
     * sweep), and released on completion so the next acquirer - the scheduled pass, or an operator re-running an
     * on-demand sweep - takes the lock at once instead of waiting out the ttl. The on-demand cleanup endpoint runs
     * through this too, so an admin-triggered sweep and the scheduled pass are single-writer across the fleet.
     */
    public <T> Optional<T> exclusively(String name, Instant now, Exclusive<T> body) throws IOException {
        return leases.exclusively(name, now, _ -> body.run());
    }

    /**
     * Run a void {@code body} under the named single-writer lease - the scheduled and on-demand pass path. Returns
     * {@code true} when this node held the lease and ran the body, {@code false} when a rival node holds it and the
     * body did not run. A void pass returns no value, so it cannot ride the {@link Optional}-returning overload: an
     * empty {@code Optional} there means "not acquired", which a {@code null} result would be indistinguishable from
     * (and {@code Optional.of(null)} would throw, miscounting every clean exclusive pass as a failure).
     */
    public boolean exclusively(String name, Instant now, ExclusivePass body) throws IOException {
        return exclusively(name, now, () -> {
            body.run();
            return Boolean.TRUE;
        }).isPresent();
    }

    /** A value-returning body run under the single-writer maintenance lease. It must return a non-null result: the
     *  empty {@link Optional} the caller receives signals only that a rival holds the lease, so a {@code null} would be
     *  indistinguishable from a refused acquisition - a void body uses the {@link ExclusivePass} overload instead. */
    public interface Exclusive<T> {

        T run() throws IOException;
    }

    /** A void body run under the single-writer maintenance lease by {@link #exclusively(String, Instant, ExclusivePass)}. */
    public interface ExclusivePass {

        void run() throws IOException;
    }

    /** Count one failed pass or unit on both surfaces: the per-task run state the observability seam reports FAILED
     *  from (which must work with no meter registry wired) and, when one is, the failure counter. */
    private void failure(String task) {
        schedule.failed(task);
        metrics.failure(task);
        // A pass that failed asks for itself again, an hour on: the request is the retry, and it is one small
        // object however often the pass keeps failing - never a loop of immediate re-runs.
        try {
            Requests.request(root, task, "the pass failed; retried after " + RETRY, Instant.now().plus(RETRY));
        } catch (IOException | RuntimeException unwritable) {
            LOGGER.warn("the retry request for '{}' could not be written: {}", task, unwritable.toString());
        }
    }

    /**
     * Run one task across every tenant and repository. The (tenant, repository) units fan out over the bounded
     * {@link #workers} pool instead of a single serial walk, so a large fleet's sweep completes in wall-clock time set
     * by the slowest unit rather than their sum; an exclusive pass already holds the task's single-writer {@link Lease}
     * (renewed on the {@link LeaseGuard}'s wall-clock cadence, independent of unit boundaries), so this parallelism
     * shards the work within the one node that owns the pass, never against a rival. A failing unit is logged and
     * counted, and the pass continues with the other repositories. The per-tenant {@link TenantContext} passes run
     * after every repository unit has settled.
     *
     * <p>{@code lost} is the live single-writer status of the pass: once it reports {@code true} the fan-out submits no
     * further batches, so the window in which two nodes sweep the same store is bounded by the units already in flight
     * rather than by the length of the whole pass. For a non-exclusive pass it is constantly {@code false}.
     */
    private void run(ScheduledTask task, Instant now, BooleanSupplier lost, Escalation escalate) throws IOException {
        // Stamp the task's last-run start and, in the finally, its finish + wall-clock duration - so the observation
        // seam surfaces each background sweep's last-run / status. The finally records the finish even when the pass
        // throws (the caller then counts the failure through failure(), which sets the FAILED flag this finish
        // preserves), and a fanned-out unit that threw already flipped the flag before this runs.
        schedule.started(task.name(), now);
        long startNanos = System.nanoTime();
        try {
            PassMetrics.Sink sink = metrics.sink();
            List<String> tenants = tenants();
            fanOut(task, tenants, now, sink, lost);
            for (String tenant : tenants) {
                if (lost.getAsBoolean()) {
                    break;
                }
                contain(task.name(), "for tenant " + tenant, escalate,
                        () -> task.task().tenant(new PassTenantContext(tenant, now)));
            }
            if (!lost.getAsBoolean()) {
                task.task().completed(now);
                metrics.flush(sink);
            }
        } finally {
            schedule.finished(task.name(), now, Duration.ofNanos(System.nanoTime() - startNanos));
        }
    }

    /**
     * Fan the (tenant, repository) units of a pass out to the bounded {@link #workers} pool in capped batches rather
     * than materialising one {@link Callable} per unit - and the matching {@link Future} list {@code invokeAll}
     * returns - up front: a large multi-tenant fleet (many tenants, each with many repositories) would otherwise hold
     * O(tenants x repositories) closures in heap before a single unit runs. The repositories are streamed per tenant
     * through the shared bounded enumeration ({@link BoundedChildren}) - which, unlike the tree walk, accepts
     * the scope root {@code ""} this pages, and which screens every name the backend hands back before it becomes a
     * key - and drained a batch at a time, so the pass holds only O({@link #FANOUT_BATCH}) units at once. Every unit
     * still runs, and because each batch is awaited before the next is built the tenant-context pass the caller runs
     * afterwards still runs only after every repository unit has settled. A unit that throws is logged and counted,
     * exactly as before - the fan-out is a footprint change, not a behaviour change.
     *
     * <p>The pass's single-writer status is re-checked between tenants, between batches and once more inside each unit
     * just before it starts: a lease lost mid-pass stops the fan-out there rather than letting the pass keep enlarging
     * the window in which two nodes sweep the same store. A unit already <em>running</em> is not interrupted - a
     * {@code MaintenanceTask} has no cancellation seam - so the residual window is one unit wide, which is the honest
     * claim. That is a visible stop, not a silent one: the caller counts the pass as failed.
     */
    private void fanOut(ScheduledTask task, List<String> tenants, Instant now, PassMetrics.Sink sink,
                        BooleanSupplier lost) throws IOException {
        List<Callable<Void>> batch = new ArrayList<>();
        for (String tenant : tenants) {
            if (lost.getAsBoolean()) {
                return;
            }
            REPOSITORIES.scan(root.scope(tenant), "", repository -> {
                if (!Repositories.valid(repository) || lost.getAsBoolean()) {
                    return;
                }
                batch.add(() -> {
                    if (lost.getAsBoolean()) {
                        // Single-writer status went away before this unit started. Running it would only widen the
                        // window in which two nodes sweep the same store, so it is skipped - the pass is already
                        // counted as failed once, by its caller.
                        return null;
                    }
                    // A unit runs on a pool thread, which has no caller either, so an Error is escalated to the
                    // operator here too (Escalation.OPERATOR): letting it out would only be captured by the
                    // FutureTask and re-surface as the ExecutionException runUnits used to discard in silence.
                    contain(task.name(), "for " + tenant + "/" + repository, Escalation.OPERATOR,
                            () -> {
                                PassRepositoryContext context =
                                        new PassRepositoryContext(tenant, repository, now, sink);
                                task.task().repository(context);
                                // Whatever the unit contained is raised here rather than by the unit, so a pass
                                // that recorded its failures cannot lose them by forgetting to re-raise.
                                context.raiseContained();
                            });
                    return null;
                });
                if (batch.size() == FANOUT_BATCH) {
                    runUnits(batch);
                    batch.clear();
                }
            });
        }
        runUnits(batch);
    }

    /** The per-tenant repository enumeration. A maintenance pass that skipped repositories would be a sweep that looks
     *  finished while some repositories were never swept, so the entry cap is OFF and the binding bound is the
     *  primitive's step budget (1000 round-trips at {@link #FANOUT_BATCH} names, ~10^6 repositories per tenant), which
     *  raises a named {@code TraversalException} that fails the pass rather than silently shortening it. */
    private static final BoundedChildren REPOSITORIES =
            BoundedChildren.bounded().entries(Integer.MAX_VALUE).page(FANOUT_BATCH);

    /** Run the fanned-out repository units across the pool and wait for all to settle. Each unit contains, names and
     *  counts its own failure, so draining the futures only guards an unexpected internal error; a pool that is
     *  shutting down (a close raced this pass) rejects the batch, and the units then run inline so the pass still
     *  completes.
     *
     *  <p>Neither residual catch is silent any more. An {@link Error} out of a unit used to be captured by the
     *  {@link FutureTask} and re-surface here as an {@link ExecutionException} that was discarded without a word - the
     *  one place in this class where a broken runtime produced no diagnostic at all - and the inline fallback caught
     *  {@code Exception}, so an {@code Error} there escaped into the worker thread instead. The unit body now contains
     *  both, and what reaches this method is logged rather than dropped. */
    private void runUnits(List<Callable<Void>> units) {
        if (units.isEmpty()) {
            return;
        }
        try {
            for (Future<Void> future : workers.invokeAll(units)) {
                future.get();
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException unexpected) {
            LOGGER.warn("A maintenance unit failed outside its own containment; the pass continues with the "
                    + "remaining repositories", unexpected);
        } catch (RejectedExecutionException _) {
            for (Callable<Void> unit : units) {
                try {
                    unit.call();
                } catch (Throwable unexpected) {
                    LOGGER.warn("A maintenance unit failed on the inline fallback the pool shutdown forced it onto; "
                            + "the pass continues with the remaining repositories", unexpected);
                }
            }
        }
    }

    private List<String> tenants() {
        List<String> tenants = new ArrayList<>();
        for (String entry : root.list("")) {
            if (Repositories.valid(entry)) {
                tenants.add(entry);
            }
        }
        return tenants;
    }

    private final class PassRepositoryContext implements RepositoryContext {

        private final String tenant;
        private final String repository;
        private final Instant now;
        private final PassMetrics.Sink sink;

        private PassRepositoryContext(String tenant, String repository, Instant now, PassMetrics.Sink sink) {
            this.tenant = tenant;
            this.repository = repository;
            this.now = now;
            this.sink = sink;
        }

        /** What this unit contained, created on first ask so a pass that records nothing allocates nothing. */
        private UnitFailures contained;

        @Override
        public UnitFailures failures(String work, String consequence) {
            if (contained == null) {
                contained = new UnitFailures(work, consequence);
            }
            return contained;
        }

        /** Raise what the unit contained. One unit, one accumulator, one thread (clause 1), so no synchronisation. */
        private void raiseContained() throws IOException {
            if (contained != null) {
                contained.rethrow();
            }
        }

        @Override
        public String tenant() {
            return tenant;
        }

        @Override
        public String repository() {
            return repository;
        }

        @Override
        public ArtifactStore store() {
            return repositories.store(tenant, repository);
        }

        @Override
        public UnaryOperator<String> config() {
            // Resolve this pass's tenant's effective value, so a tenant-overridable setting takes effect in the sweep;
            // a global-only key resolves deployment-wide through the same chain, unchanged.
            return key -> tenantConfig.apply(tenant, key);
        }

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public void gauge(String name, String description, Map<String, String> tags, double value) {
            sink.gauge(name, description, tags, value);
        }

        @Override
        public void counter(String name, String description, Map<String, String> tags, double amount) {
            sink.counter(name, description, tags, amount);
        }
    }

    private final class PassTenantContext implements TenantContext {

        private final String tenant;
        private final Instant now;

        private PassTenantContext(String tenant, Instant now) {
            this.tenant = tenant;
            this.now = now;
        }

        @Override
        public String tenant() {
            return tenant;
        }

        @Override
        public ArtifactStore store() {
            return root.scope(tenant);
        }

        @Override
        public UnaryOperator<String> config() {
            // The same tenant-scoped chain the per-repository hook resolves through: the two hooks differ in which
            // store they sweep, never in which settings they can see.
            return key -> tenantConfig.apply(tenant, key);
        }

        @Override
        public long quotaLimit() throws IOException {
            return repositories.quotaLimit(tenant);
        }

        @Override
        public long recomputeQuota() throws IOException {
            return repositories.recomputeQuota(tenant);
        }

        @Override
        public Instant now() {
            return now;
        }
    }
}
