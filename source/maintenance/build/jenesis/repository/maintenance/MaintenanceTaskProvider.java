package build.jenesis.repository.maintenance;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Providers;
import build.jenesis.repository.icon.IconContributor;

/**
 * A named factory for a {@link MaintenanceTask}, discovered at runtime with {@link ServiceLoader} - so a background
 * pass is a drop-in module that {@code provides} this interface, and the scheduler names no task. Each provider
 * reads its own enablement and interval through the {@code config} lookup (a property/setting accessor returning
 * {@code null} when unset), staying free of any framework dependency, and yields empty when its pass is not
 * enabled. With no task enabled the scheduler stays idle; with none installed the deployment simply has no
 * background maintenance.
 *
 * <p>The cadence every provider reads is one {@link IntervalSetting} constant per dial, resolved inside
 * {@code create}. That is not a style preference: {@link #resolve(UnaryOperator)} runs at boot and is
 * <em>strict</em>, so a provider that parses its own dial and throws on a typo takes the whole deployment down with
 * it. {@link IntervalSetting#resolve} never throws, which is what keeps a typo'd dial from ever reaching that.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> A provider is instantiated by {@link ServiceLoader} and {@code create} may be called
 *       concurrently with a running pass - {@link #resolveContained} re-runs on every settings-convergence tick while
 *       the worker is sweeping. A provider must therefore be stateless, or guard whatever state it latches (the
 *       {@code forwarding} / {@code webhook} enablement latches are the only ones that do).</li>
 *   <li><b>Idempotency / replay.</b> {@code create} is called repeatedly over the life of a deployment and must be a
 *       pure function of {@code config}: the same configuration yields an equivalent task. It must not perform I/O,
 *       write to the store, or assume it is called once. The <em>task</em> it builds must converge idempotently on
 *       re-run and back-fill from durable state when enabled late (&sect;5); the provider itself does no work.</li>
 *   <li><b>Absence sentinel.</b> {@code create} returns an empty {@link Optional} when the pass is switched off;
 *       {@code null} is never legal. Disabling has exactly <em>two</em> routes and gains no third: the neutral
 *       {@code jenreg.<name>=false} toggle plus the {@link #requiredConfig()} self-disable, both applied by
 *       {@link Features#active} before a provider is asked, and the provider's own enablement setting returning empty.
 *       A cadence of zero is <em>not</em> a disable route (see {@link IntervalSetting}).</li>
 *   <li><b>Construction failure is phase-dependent (&sect;9).</b> A provider's own value or construction failure (a
 *       malformed dial, an unparseable policy, a constructor that throws) is <em>fatal at boot</em> and
 *       <em>contained on refresh</em>, and the two phases are two entry points rather than one flag:
 *       {@link #resolve} is what a deployment boots through and it <strong>fails</strong>, naming the provider;
 *       {@link #resolveContained} is what the settings-convergence tick of a <em>running</em> deployment re-resolves
 *       through, and it disables that one provider, loudly, while every other pass still schedules. The asymmetry is
 *       the point: a boot failure is only <em>conditionally</em> recoverable - a stored setting heals when an operator
 *       fixes it and the next tick picks the pass up, but an environment variable, a config file or a transient
 *       condition has nothing to trigger a re-resolve, so a contained boot would serve a permanently incomplete
 *       deployment until someone noticed. At refresh the trade reverses: the server is already serving, a settings
 *       edit must not take a running deployment down, and per-provider containment lets the other toggles in the same
 *       write converge instead of being held hostage by one bad provider.</li>
 *   <li><b>Selection failure is never contained (&sect;9).</b> An {@link IllegalStateException} - what the shared
 *       provider-resolution primitives throw when an operator explicitly named a backend that is not installed, or
 *       when two providers claim one name - propagates out of <em>both</em> entry points, unwrapped, naming the
 *       selection. Those are deployment mistakes rather than one plugin's bad day: "the feed you selected does not
 *       exist" must never degrade into a silent fallback, on any path, and a duplicate-name packaging error is not
 *       something a convergence tick can heal.</li>
 *   <li><b>Error visibility (&sect;9).</b> A contained failure is never silent. It is logged at {@code WARNING} naming
 *       the provider and the cause, and the provider is listed in the resolve's {@link Contained#unavailable()}, so
 *       the scheduler that resolved it
 *       reports the pass as <em>failed</em> rather than letting it vanish from a silently shorter task list. The
 *       report is the <em>last</em> resolve's, replaced wholesale, so a provider that starts resolving again drops out
 *       of it on the next convergence tick.</li>
 *   <li><b>Ordering / determinism.</b> Both entry points return the enabled tasks sorted by name, so the schedule is
 *       the same on every node regardless of module-path discovery order. A provider's {@link #name()} is its task
 *       name, its toggle key and its {@code locks/<name>} lease object; it is part of the deployment's wire contract
 *       and renaming one in a mixed-version fleet means two sweepers on one store.</li>
 *   <li><b>Lifecycle / ownership.</b> {@link ServiceLoader} instances are created per {@code resolve} call and are not
 *       cached; a provider must not own a thread, a client or any resource it expects to be closed. A pass that needs
 *       one owns it inside the {@link MaintenanceTask} it builds, whose lifecycle the scheduler drives.</li>
 *   <li><b>Bounded work.</b> {@code create} is called once per provider per resolve and must return promptly: it runs
 *       on the boot thread and on every settings-convergence tick.</li>
 * </ol>
 */
public interface MaintenanceTaskProvider extends IconContributor {

    /** The task name this provider answers to, e.g. {@code cleanup}, {@code scan}. */
    String name();

    /** Build the task if the configuration enables it, reading settings through {@code config}; empty when off. */
    Optional<MaintenanceTask> create(UnaryOperator<String> config);

    /** Every task this provider contributes - one, by default, {@link #create}'s; a provider that schedules several
     *  passes under one toggle (the walks, one per configured entry) answers them all here, each with a name of its
     *  own, which is its toggle's, its lease's and its report's. */
    default List<MaintenanceTask> tasks(UnaryOperator<String> config) {
        return create(config).map(List::of).orElse(List.of());
    }

    /** The config keys this task cannot run without; empty (the default) for one that needs nothing. A provider
     *  whose required keys are unset {@link Features#active self-disables} at discovery with one log line. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /**
     * Every enabled task discovered via {@link ServiceLoader}, ordered by name for a deterministic schedule, resolved
     * <strong>strictly</strong>: a provider whose {@code create} throws fails this call, naming the provider. This is
     * the entry point a deployment <em>boots</em> through - the application resolves it once inside a
     * {@code @Bean} method - so a pass that cannot be built stops the server rather than being served around.
     *
     * <p>Strict here and {@linkplain #resolveContained contained} there, because a boot failure is only
     * <em>conditionally</em> recoverable. The convergence tick that would pick the pass up again runs only when the
     * <em>stored</em> settings change, so a failure caused by a stored setting heals when an operator fixes it - but
     * one caused by an environment variable, a config file or a transient condition at boot has nothing to trigger a
     * re-resolve and would stay unscheduled until a restart. An operator who meant to run without the pass says so
     * ({@code jenreg.<name>=false}) and restarts; an operator who did not gets told at once instead of
     * discovering a silently incomplete deployment later on an observability screen.
     *
     * <p>A failure is reported as an {@link IllegalStateException} naming the provider, its implementing class and the
     * cause, so the bean-creation failure an operator reads says which pass to fix. A provider's own
     * {@link IllegalStateException} - a &sect;9 selection failure, or two providers claiming one name - propagates
     * unwrapped, since its message already names the selection.
     */
    static List<MaintenanceTask> resolve(UnaryOperator<String> config) {
        List<MaintenanceTask> tasks = discover(config, (provider, misconfigured) -> {
            throw new IllegalStateException("Maintenance pass '" + provider.name() + "' could not be built: its "
                    + "provider (" + provider.getClass().getName() + ") failed - " + cause(misconfigured)
                    + ". A pass that cannot be built at startup fails the boot rather than being dropped: unless the "
                    + "setting it reads is a stored one, nothing on a running deployment would re-resolve it and the "
                    + "pass would stay unscheduled until a restart. Fix the setting it reads, or disable the pass "
                    + "explicitly with jenreg." + provider.name() + "=false.", misconfigured);
        });
        return tasks;
    }

    /**
     * The same discovery, resolved <strong>contained</strong>: a provider that throws while reading its own
     * configuration disables <em>itself</em>, loudly, and every other pass still schedules. This is the entry point a
     * <em>running</em> deployment re-resolves through on each settings-convergence tick, where the trade is the
     * opposite of {@link #resolve}'s: the server is already serving, so a settings edit must not take it down, and
     * per-provider containment lets the other toggles in the same write converge instead of being held hostage by one
     * bad provider. The failure is not silent - it is logged at {@code WARNING} and listed in the returned
     * {@link Contained#unavailable()}, so the scheduler that asked reports the pass as failed rather than letting it
     * vanish from a shorter list.
     *
     * <p>Containment covers a provider's own value or construction failure, including one that originates in another
     * repository's code - a malformed {@code jenreg.gc.grace} reaching the garbage collector through the
     * retention provider - which no provider-local {@code catch} in this repository could fix.
     *
     * <p>An {@link IllegalStateException} is deliberately <em>not</em> contained here either: that is what the shared
     * provider-resolution primitives raise for a &sect;9 selection failure (an operator named a backend that is not
     * installed) or a packaging error (two providers claiming one name). It aborts the whole re-resolve rather than
     * disabling one pass, so the caller keeps its last good task list whole instead of converging half of a
     * deployment mistake.
     */
    static Contained resolveContained(UnaryOperator<String> config) {
        Map<String, String> unavailable = new TreeMap<>();
        List<MaintenanceTask> tasks = discover(config, (provider, misconfigured) -> {
            String cause = cause(misconfigured);
            unavailable.put(provider.name(), cause);
            System.getLogger(MaintenanceTaskProvider.class.getName()).log(System.Logger.Level.WARNING,
                    "Maintenance pass '" + provider.name() + "' is not scheduled: its provider ("
                            + provider.getClass().getName() + ") failed to build the task - " + cause
                            + ". Every other maintenance pass is unaffected; fix the setting this provider reads "
                            + "and the pass is picked up on the next settings-convergence tick.", misconfigured);
        });
        return new Contained(tasks, unavailable);
    }

    /**
     * What a {@linkplain #resolveContained contained resolve} built, and the providers it had to leave out, by name
     * with a one-line cause - so whoever resolved can report a misconfigured pass as <em>failed</em> rather than
     * silently absent. It belongs to the resolve and to whoever asked for it: a static "last resolve" would make two
     * schedulers in one JVM report each other's failures.
     */
    record Contained(List<MaintenanceTask> tasks, Map<String, String> unavailable) {

        public Contained {
            tasks = List.copyOf(tasks);
            unavailable = Map.copyOf(unavailable);
        }

        /** A fixed task list, which leaves nothing out. */
        public static Contained of(List<MaintenanceTask> tasks) {
            return new Contained(tasks, Map.of());
        }
    }

    /** The one discovery loop both entry points run; {@code onFailure} is the <em>only</em> difference between them -
     *  {@link #resolve} rethrows out of it, {@link #resolveContained} records and warns. The &sect;9 carve-out lives
     *  here rather than in either caller, so a selection failure cannot be contained by construction on either path. */
    private static List<MaintenanceTask> discover(UnaryOperator<String> config,
                                                  BiConsumer<MaintenanceTaskProvider, RuntimeException> onFailure) {
        List<MaintenanceTaskProvider> discovered = new ArrayList<>();
        ServiceLoader.load(MaintenanceTaskProvider.class).forEach(discovered::add);
        // Read every name ONCE, before anything is decided on it. A pass name is its toggle key, its lease object and
        // its attribution in every diagnostic below, and it used to be re-read at each of those points - so a provider
        // whose name() answered differently on two calls could be enabled under one name and reported under another.
        Map<MaintenanceTaskProvider, String> names = new IdentityHashMap<>();
        for (MaintenanceTaskProvider provider : discovered) {
            names.put(provider, provider.name());
        }
        List<MaintenanceTask> tasks = new ArrayList<>();
        for (List<MaintenanceTask> contributed : Providers.all("maintenance", discovered, names::get,
                provider -> Features.active(config, names.get(provider), provider.requiredConfig()),
                provider -> {
                    try {
                        List<MaintenanceTask> built = provider.tasks(config);
                        return built.isEmpty() ? Optional.<List<MaintenanceTask>>empty() : Optional.of(built);
                    } catch (IllegalStateException selection) {
                        throw selection;
                    } catch (RuntimeException misconfigured) {
                        onFailure.accept(provider, misconfigured);
                        return Optional.empty();
                    } catch (Error broken) {
                        // NOT contained - and named on its way out, which is the half that was missing. An Error is
                        // the runtime or the module graph giving way (a LinkageError from a half-installed plugin)
                        // rather than a provider declining to build its task, so it reaches the caller instead of
                        // disabling one pass and leaving a deployment that looks whole. What it must not do is arrive
                        // anonymous: this loop used to catch RuntimeException only, so an Error left with nothing
                        // saying WHICH of the installed providers raised it, and an operator whose boot failed learned
                        // that maintenance resolution died and not which plugin killed it.
                        //
                        // Attribute-and-rethrow rather than contain, matching Contributions' Error arm exactly - the
                        // peer this entry cites. The blast-radius argument for containing it was withdrawn when the
                        // claim underneath it turned out to be false: Spring's repeating-task error handler logs and
                        // suppresses, so an Error out of a convergence tick costs that tick, not the schedule.
                        //
                        // Suppressed rather than logged, so the name survives every log configuration and rides the
                        // stack trace the caller already prints.
                        try {
                            broken.addSuppressed(new MaintenancePassFailure(names.get(provider), provider));
                        } catch (Throwable diagnostic) {
                            // Naming it must never replace it: composing the marker can itself fail on the very
                            // runtime that just gave way, and an unnamed Error still beats a swallowed one.
                            broken.addSuppressed(diagnostic);
                        }
                        throw broken;
                    }
                })) {
            tasks.addAll(contributed);
        }
        // Providers.all orders by PROVIDER name and this orders by TASK name. The contract makes them the same string,
        // so the sort is a no-op on a well-behaved provider - and it is kept because a provider that breaks that
        // promise should still produce one schedule order on every node rather than two.
        tasks.sort(Comparator.comparing(MaintenanceTask::name));
        return List.copyOf(tasks);
    }

    /** One line naming what an operator has to fix: the exception's simple name and its message. */
    private static String cause(RuntimeException failure) {
        return failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
    }

    /** The provider names installed on this deployment, regardless of enablement - the capability signal a console
     *  or API gates its surface on. */
    static Set<String> installed() {
        // Through the primitive, not a TreeSet: a set silently MERGES two providers claiming one name, which is the
        // capability surface agreeing that the duplicate is one pass. The shared validation refuses it instead.
        return Providers.installedNames("maintenance", ServiceLoader.load(MaintenanceTaskProvider.class),
                MaintenanceTaskProvider::name, provider -> true);
    }
}
