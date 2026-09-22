package build.jenesis.repository.cleanup;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Providers;

/**
 * A named factory for a {@link RetentionSweeper}, discovered at runtime with {@link ServiceLoader} - so the
 * retention engine is a drop-in module that {@code provides} this interface, and the composition names no engine.
 * Each provider reads its own configuration through the {@code config} lookup (a property/setting accessor
 * returning {@code null} when unset), staying free of any framework dependency, and yields empty when its engine is
 * not enabled. With no provider installed, {@link #resolve} is empty and retention degrades: the cleanup and
 * retention endpoints answer {@code 501} and the console hides the retention surface.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> {@link #name()} and {@link #requiredConfig()} are pure declarations; {@link #create}
 *     runs on the resolving thread. The {@link RetentionSweeper} it returns is shared by the scheduled sweep and
 *     the preview endpoint, so <em>that</em> object must be thread-safe.</li>
 * <li><b>Idempotency / replay.</b> A sweep is plan-then-apply and converges: re-running it after a crash evicts
 *     what the policy still selects and never re-deletes what is already gone or deletes twice what a partial run
 *     already removed.</li>
 * <li><b>Absence sentinel.</b> An empty {@link Optional} is the sentinel, from {@link #create} and from
 *     {@link #resolve} alike: the cleanup and retention endpoints answer {@code 501} and the console hides the
 *     surface. {@code null} is never a legal return from {@link #name()}, {@link #create} or
 *     {@link #requiredConfig()}.</li>
 * <li><b>Selection failure (&sect;9).</b> An explicit {@code jenreg.retention=<name>} that no installed
 *     engine answers to - its module is off the path, its name is misspelled - or whose provider declines because
 *     its {@link #requiredConfig() required configuration} is unset throws {@link IllegalStateException} at
 *     resolution, naming the selection and the installed engine names. It does <em>not</em> degrade to
 *     no-retention: that would leave the endpoints answering {@code 501} while artifacts the operator meant to age
 *     out are held forever with nothing said. An explicit selection outranks the
 *     {@code jenreg.<name>=false} toggle; only an <em>unselected</em> deployment degrades to empty, and
 *     two <em>enabled</em> engines with no selection are ambiguous and throw rather than resolving by discovery
 *     order.</li>
 * <li><b>Tenant scoping (&sect;6).</b> The sweeper applies its plan through a repository's own inventory, so every
 *     eviction is scoped to the tenant and repository the caller names.</li>
 * <li><b>Error visibility (&sect;9).</b> An eviction that fails is surfaced rather than swallowed: retention
 *     deletes durable content, so a partially applied plan must be visible in the sweep's outcome rather than
 *     reported as a clean pass.</li>
 * <li><b>Lifecycle / ownership.</b> The composition resolves the sweeper once and owns it; {@link #resolve} builds
 *     at most one instance per call, caches nothing and closes nothing.</li>
 * <li><b>Ordering / determinism.</b> The resolved engine is a function of the configuration and the installed
 *     providers only, never of discovery order.</li>
 * <li><b>Bounded work / cancellation.</b> A plan is computed before anything is deleted and can be previewed, so a
 *     sweep never presents a truncated pass as a complete one; a lease keeps a second node from sweeping the same
 *     repository concurrently. The {@link RepositoryInventory} a sweeper drives must deliver
 *     {@link RepositoryInventory#releases(RepositoryInventory.ReleaseVisitor)} grouped and streamed from its own key
 *     tree, so the plan holds one coordinate's versions rather than the repository's whole release list; the
 *     inherited default is the list-backed fallback, and its bound is <em>visible</em> - it refuses with an
 *     {@link IllegalStateException} naming the class and the override rather than buffering a deployment-sized
 *     release set.</li>
 * </ol>
 */
public interface RetentionProvider {

    /** The engine name this provider answers to, e.g. {@code cleaner}. */
    String name();

    /** Build the sweeper if the configuration enables it, reading settings through {@code config}; empty when off. */
    Optional<RetentionSweeper> create(UnaryOperator<String> config);

    /** The config keys this engine cannot run without; empty (the default) for one that needs nothing. A provider
     *  whose required keys are unset {@link Features#active self-disables} at discovery with one log line. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /** The configured engine, resolved through the shared {@link Providers#optionalUnique} policy: an explicit
     *  {@code jenreg.retention=<name>} selects one by name and a selection nothing can honour
     *  <em>throws</em> rather than degrading to no-retention (PRINCIPLES §9), a
     *  {@code jenreg.<name>=false} or an unset {@link #requiredConfig()} switches one off, more than one
     *  enabled engine is ambiguous rather than a discovery-order winner, and only an <em>unselected</em> deployment
     *  with nothing enabled degrades to empty. */
    /** The names of every installed retention engine - the keys {@code jenreg.<name>} switches off - for the
     *  settings catalogue to list. */
    static Set<String> installed() {
        return Providers.installedNames("retention",
                ServiceLoader.load(RetentionProvider.class),
                RetentionProvider::name,
                _ -> true);
    }

    static Optional<RetentionSweeper> resolve(UnaryOperator<String> config) {
        return Providers.optionalUnique("retention",
                ServiceLoader.load(RetentionProvider.class),
                RetentionProvider::name,
                Features.selection(config, "retention"),
                provider -> Features.active(config, provider.name(), provider.requiredConfig()),
                provider -> provider.create(config));
    }
}
