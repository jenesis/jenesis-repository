package build.jenesis.repository.server.spi;

import module java.base;

import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Providers;

/**
 * A named factory for a {@link KeyUsageTracker}, discovered at runtime with {@link ServiceLoader} - so credential
 * usage tracking is a drop-in module and the composition names no implementation. Each provider reads its own
 * configuration through the {@code config} lookup (a property accessor returning {@code null} when unset) and
 * records through the given {@link Authorization}. With no module installed, {@link #resolve} answers
 * {@link KeyUsageTracker#NONE}: nothing records and the worker reports as off.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> {@link #name()} and {@link #requiredConfig()} are pure declarations callable from any
 *     thread; {@link #create} runs once, on the boot thread. The {@link KeyUsageTracker} it returns is a shared
 *     singleton recorded into from every request thread while its own worker drains asynchronously, so <em>that</em>
 *     object must be thread-safe and must not block the recording thread.</li>
 * <li><b>Idempotency / replay.</b> {@link #create} is a pure factory: building a tracker starts no worker and writes
 *     nothing, so resolving twice is safe. A replayed drain of the same accumulated hits must converge on the same
 *     persisted count rather than double-counting.</li>
 * <li><b>Absence sentinel.</b> The unselected absence of a tracker module is not an error: {@link #resolve} answers
 *     {@link KeyUsageTracker#NONE} - never {@code null}, never an exception - nothing is recorded and the worker
 *     reports as off, so a console shows "not tracked" rather than an ambiguous zero. {@link #create} declines with
 *     an empty {@link Optional}; {@code null} is never a legal return from it, from {@link #name()} or from
 *     {@link #requiredConfig()}.</li>
 * <li><b>Selection failure (&sect;9).</b> An <em>explicitly selected</em> {@code jenreg.key-usage=<name>}
 *     that no installed provider answers to, or whose provider declines, throws {@link IllegalStateException} at
 *     resolution naming the selection and the installed provider names - it does <em>not</em> resolve to
 *     {@link KeyUsageTracker#NONE}. An operator who asked for usage tracking and silently got none would read every
 *     credential's "last used: never" as evidence it is safe to revoke. An explicit selection outranks the
 *     {@code jenreg.<name>=false} toggle. Only an <em>unselected</em> deployment degrades to the
 *     sentinel.</li>
 * <li><b>Tenant scoping (&sect;6).</b> Every recorded hit carries its tenant and credential hash, and a tracker
 *     persists through the {@link Authorization} it was handed - it never widens a record beyond the tenant the
 *     request authenticated as.</li>
 * <li><b>Error visibility (&sect;9).</b> Nothing is swallowed at resolution: duplicate provider names, one provider
 *     registered twice, and more than one <em>enabled</em> tracker with no selection to disambiguate them all throw,
 *     naming the candidates and the setting that resolves them. Recording itself is explicitly <b>best-effort</b>:
 *     the drain is off the request path and a lost batch may only <em>under-count</em> uses or leave "last used"
 *     stale. It may never fail a request, and it may never make a credential look <em>more</em> used than it was -
 *     an inflated count would mask an unused key an operator would otherwise revoke.</li>
 * <li><b>Lifecycle / ownership.</b> The composition owns the resolved tracker: {@link #resolve} builds at most one
 *     instance per call and hands it over, caching nothing. A tracker owns its drain thread and is closed by the
 *     composition, flushing what it has accumulated; the provider instance is created by {@link ServiceLoader},
 *     consulted and discarded.</li>
 * <li><b>Ordering / determinism.</b> The resolved tracker is a function of the configuration and the installed
 *     providers only, never of discovery order. Drain order across credentials is unspecified; only the converged
 *     per-credential count and the most recent address are contractual.</li>
 * <li><b>Durability / delivery.</b> Best-effort, batched: a hit is durable only once a drain has written it through
 *     {@link Authorization}, so the accumulate-to-flush window is lost on a crash by design. The durable source of
 *     truth is the credential record, and the tracker heals by simply accumulating again - there is no replay log
 *     and none is promised.</li>
 * </ol>
 */
public interface KeyUsageTrackerProvider {

    /** The tracker name this provider answers to, e.g. {@code batching}. */
    String name();

    /** Build the tracker over the deployment's {@link Authorization}, reading settings through {@code config};
     *  empty when off. */
    Optional<KeyUsageTracker> create(Authorization authorization, UnaryOperator<String> config);

    /** The config keys this tracker cannot run without; empty (the default) for one that needs nothing. A provider
     *  whose required keys are unset {@link Features#active self-disables} at discovery. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /** The single enabled tracker discovered via {@link ServiceLoader}, resolved through the shared
     *  {@link Providers#optionalUnique} policy: an explicit {@code jenreg.key-usage=<name>} selects one by
     *  name and a selection nothing answers to <em>throws</em> rather than degrading (&sect;9), a
     *  {@code jenreg.<name>=false} switches one off, more than one enabled tracker is ambiguous rather
     *  than a discovery-order winner, and only an <em>unselected</em> deployment with no tracker installed resolves to
     *  {@link KeyUsageTracker#NONE}. */
    static KeyUsageTracker resolve(Authorization authorization, UnaryOperator<String> config) {
        return Providers.<KeyUsageTrackerProvider, KeyUsageTracker>optionalUnique("key-usage",
                        ServiceLoader.load(KeyUsageTrackerProvider.class),
                        KeyUsageTrackerProvider::name,
                        Features.selection("key-usage"),
                        provider -> Features.active(provider.name(), provider.requiredConfig()),
                        provider -> provider.create(authorization, config))
                .orElse(KeyUsageTracker.NONE);
    }
}
