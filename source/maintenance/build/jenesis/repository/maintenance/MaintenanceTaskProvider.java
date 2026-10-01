package build.jenesis.repository.maintenance;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Providers;
import build.jenesis.repository.icon.IconContributor;

/**
 * A named factory for a {@link MaintenanceTask}, discovered with {@link ServiceLoader}, so a background pass is a
 * module that {@code provides} this interface and the scheduler names no task. A provider reads its enablement and
 * interval through the {@code config} lookup ({@code null} when unset) and yields empty when its pass is off.
 *
 * <p>Each cadence is read through an {@link IntervalSetting} constant, whose {@link IntervalSetting#resolve} never
 * throws: {@link #resolve(UnaryOperator)} is strict at boot, so a provider parsing its own dial would let a typo stop
 * the deployment.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> {@code create} may run concurrently with a running pass, since
 *       {@link #resolveContained} re-runs on every settings-convergence tick. A provider is stateless or guards what it
 *       latches.</li>
 *   <li><b>Idempotency / replay.</b> {@code create} is called repeatedly and is a pure function of {@code config}: no
 *       I/O, no store write. The task it builds converges idempotently on re-run and back-fills from durable state
 *       when enabled late.</li>
 *   <li><b>Absence sentinel.</b> {@code create} returns an empty {@link Optional} when the pass is off, never
 *       {@code null}. A pass is disabled by the {@code jenrepo.<name>=false} toggle or an unset
 *       {@link #requiredConfig()} key, both applied by {@link Features#active}, or by its own enablement setting; a
 *       cadence of zero does not disable (see {@link IntervalSetting}).</li>
 *   <li><b>Construction failure is phase-dependent.</b> A provider's own failure (a malformed dial, a constructor that
 *       throws) fails the boot through {@link #resolve}, naming the provider, and disables only that provider through
 *       {@link #resolveContained} on a running deployment. At boot, a failure caused by an environment variable,
 *       a config file or a transient condition has nothing that would re-resolve it; on a running deployment, a
 *       settings edit must not take the server down or hold the other toggles of the same write hostage.</li>
 *   <li><b>Selection failure is never contained.</b> An {@link IllegalStateException} - an explicitly named backend
 *       that is not installed, or two providers claiming one name - propagates unwrapped from both entry points.</li>
 *   <li><b>Error visibility.</b> A contained failure is logged at {@code WARNING} naming the provider and the cause,
 *       and listed in {@link Contained#unavailable()}, so the scheduler reports the pass as failed. The report is the
 *       last resolve's, so a provider that recovers drops out of it on the next tick.</li>
 *   <li><b>Ordering / determinism.</b> Both entry points return the enabled tasks sorted by name, the same on every
 *       node. A provider's {@link #name()} is its task name, toggle key and {@code locks/<name>} lease object; renaming
 *       one in a mixed-version fleet means two sweepers on one store.</li>
 *   <li><b>Lifecycle / ownership.</b> Providers are instantiated per resolve and not cached, so a provider owns no
 *       thread, client or closeable resource; a pass that needs one owns it inside its {@link MaintenanceTask}.</li>
 *   <li><b>Bounded work.</b> {@code create} returns promptly: it runs on the boot thread and on every
 *       settings-convergence tick.</li>
 * </ol>
 */
public interface MaintenanceTaskProvider extends IconContributor {

    /** The task name this provider answers to, e.g. {@code cleanup}, {@code scan}. */
    String name();

    /** Build the task if the configuration enables it, reading settings through {@code config}; empty when off. */
    Optional<MaintenanceTask> create(UnaryOperator<String> config);

    /** Every task this provider contributes, by default {@link #create}'s; a provider scheduling several passes under
     *  one toggle (the walks) answers them all, each with a name of its own for its lease and its report. */
    default List<MaintenanceTask> tasks(UnaryOperator<String> config) {
        return create(config).map(List::of).orElse(List.of());
    }

    /** The config keys this task cannot run without; empty (the default) for one that needs nothing. A provider
     *  whose required keys are unset {@link Features#active self-disables} at discovery with one log line. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /**
     * Every enabled task, ordered by name, resolved strictly: a provider whose {@code create} throws fails this call
     * with an {@link IllegalStateException} naming the provider, its class and the cause. A deployment boots through
     * this, so a pass that cannot be built stops the server rather than being served around (see the contract for
     * why). A provider's own {@link IllegalStateException} propagates unwrapped.
     */
    static List<MaintenanceTask> resolve(UnaryOperator<String> config) {
        List<MaintenanceTask> tasks = discover(config, (provider, misconfigured) -> {
            throw new IllegalStateException("Maintenance pass '" + provider.name() + "' could not be built: its "
                    + "provider (" + provider.getClass().getName() + ") failed - " + cause(misconfigured)
                    + ". A pass that cannot be built at startup fails the boot rather than being dropped: unless the "
                    + "setting it reads is a stored one, nothing on a running deployment would re-resolve it and the "
                    + "pass would stay unscheduled until a restart. Fix the setting it reads, or disable the pass "
                    + "explicitly with jenrepo." + provider.name() + "=false.", misconfigured);
        });
        return tasks;
    }

    /**
     * The same discovery, resolved contained: a provider that throws while building its task is logged at
     * {@code WARNING}, listed in {@link Contained#unavailable()} and left out, and every other pass still schedules.
     * A running deployment re-resolves through this on each settings-convergence tick. Containment covers failures
     * originating in another module's code, such as a malformed {@code jenrepo.gc.grace} reaching the collector
     * through the retention provider. An {@link IllegalStateException} aborts the whole re-resolve, so the caller
     * keeps its last good task list.
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
     * What a {@linkplain #resolveContained contained resolve} built, and the providers it left out with a one-line
     * cause each. It is returned rather than held statically, so two schedulers in one JVM never report each other's
     * failures.
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

    /** The discovery loop both entry points run, differing only in {@code onFailure}. A selection failure is rethrown
     *  here, so neither path can contain it. */
    private static List<MaintenanceTask> discover(UnaryOperator<String> config,
                                                  BiConsumer<MaintenanceTaskProvider, RuntimeException> onFailure) {
        List<MaintenanceTaskProvider> discovered = new ArrayList<>();
        ServiceLoader.load(MaintenanceTaskProvider.class).forEach(discovered::add);
        // Each name is read once, so a provider is enabled, leased and reported under one name.
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
                        // An Error is the runtime or module graph giving way (a LinkageError from a half-installed
                        // plugin), so it is rethrown rather than contained, with the provider attached as a suppressed
                        // marker so the stack trace names which plugin raised it.
                        try {
                            broken.addSuppressed(new MaintenancePassFailure(names.get(provider), provider));
                        } catch (Throwable diagnostic) {
                            // Composing the marker can fail on the same broken runtime; the Error is still rethrown.
                            broken.addSuppressed(diagnostic);
                        }
                        throw broken;
                    }
                })) {
            tasks.addAll(contributed);
        }
        // Providers.all orders by provider name; task names normally match, and this keeps one order on every node when
        // they do not.
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
        // The primitive refuses two providers claiming one name, which a set would silently merge.
        return Providers.installedNames("maintenance", ServiceLoader.load(MaintenanceTaskProvider.class),
                MaintenanceTaskProvider::name, provider -> true);
    }
}
