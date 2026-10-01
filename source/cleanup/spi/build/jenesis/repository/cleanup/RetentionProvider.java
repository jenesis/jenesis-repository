package build.jenesis.repository.cleanup;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Providers;

/**
 * A named factory for a {@link RetentionSweeper}, discovered with {@link ServiceLoader}, so the composition names no
 * engine. Each provider reads its configuration through the {@code config} lookup and yields empty when its engine is
 * not enabled. With no provider installed {@link #resolve} is empty: the cleanup and retention endpoints answer
 * {@code 501} and the console hides the retention surface.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> {@link #name()} and {@link #requiredConfig()} are pure declarations; {@link #create} runs
 *       on the resolving thread. The returned sweeper is shared by the scheduled sweep and the preview endpoint, so it
 *       is thread-safe.</li>
 *   <li><b>Idempotency / replay.</b> A sweep is plan-then-apply and converges: a re-run after a crash evicts what the
 *       policy still selects and never deletes twice.</li>
 *   <li><b>Absence sentinel.</b> An empty {@link Optional}, from {@link #create} and {@link #resolve} alike.
 *       {@code null} is never returned from {@link #name()}, {@link #create} or {@link #requiredConfig()}.</li>
 *   <li><b>Selection failure.</b> An explicit {@code jenrepo.retention=<name>} that no installed engine answers, or
 *       whose required configuration is unset, throws {@link IllegalStateException} at resolution naming the selection
 *       and the installed names - never degrading to no retention, which would keep what the operator meant to age out
 *       with nothing said. An explicit selection outranks the {@code jenrepo.<name>=false} toggle; only an unselected
 *       deployment degrades to empty, and two enabled engines with no selection throw as ambiguous.</li>
 *   <li><b>Tenant scoping.</b> The sweeper applies its plan through a repository's own inventory, so every eviction is
 *       scoped to the tenant and repository the caller names.</li>
 *   <li><b>Error visibility.</b> A failed eviction is surfaced in the sweep's outcome: retention deletes durable
 *       content, so a partially applied plan is never reported as clean.</li>
 *   <li><b>Lifecycle / ownership.</b> The composition resolves the sweeper once and owns it; {@link #resolve} builds at
 *       most one instance per call and caches and closes nothing.</li>
 *   <li><b>Ordering / determinism.</b> The resolved engine depends on the configuration and the installed providers,
 *       never on discovery order.</li>
 *   <li><b>Bounded work / cancellation.</b> A plan is computed before anything is deleted and can be previewed; a lease
 *       keeps two nodes from sweeping one repository at once. The {@link RepositoryInventory} a sweeper drives delivers
 *       {@link RepositoryInventory#releases(RepositoryInventory.ReleaseVisitor)} grouped and streamed, so the plan
 *       holds one coordinate's versions; the inherited list-backed default refuses past its bound rather than buffering
 *       a deployment-sized set.</li>
 * </ol>
 */
public interface RetentionProvider {

    /** The engine name this provider answers to, e.g. {@code cleaner}. */
    String name();

    /** Build the sweeper if the configuration enables it, reading settings through {@code config}; empty when off. */
    Optional<RetentionSweeper> create(UnaryOperator<String> config);

    /** The config keys this engine cannot run without; empty by default. A provider whose required keys are unset
     *  {@link Features#active self-disables} at discovery with one log line. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /** The names of every installed retention engine - the keys {@code jenrepo.<name>} switches off - for the settings
     *  catalogue. */
    static Set<String> installed() {
        return Providers.installedNames("retention",
                ServiceLoader.load(RetentionProvider.class),
                RetentionProvider::name,
                _ -> true);
    }

    /** The configured engine, through the shared {@link Providers#optionalUnique} policy: an explicit
     *  {@code jenrepo.retention=<name>} nothing can honour throws, {@code jenrepo.<name>=false} or an unset
     *  {@link #requiredConfig()} switches one off, two enabled engines are ambiguous, and only an unselected
     *  deployment with nothing enabled resolves empty. */
    static Optional<RetentionSweeper> resolve(UnaryOperator<String> config) {
        return Providers.optionalUnique("retention",
                ServiceLoader.load(RetentionProvider.class),
                RetentionProvider::name,
                Features.selection(config, "retention"),
                provider -> Features.active(config, provider.name(), provider.requiredConfig()),
                provider -> provider.create(config));
    }
}
