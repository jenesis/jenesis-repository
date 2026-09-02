package build.jenesis.repository.server.spi;

import module java.base;

import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Providers;

/**
 * A named factory for a {@link RateLimiter}, discovered at runtime with {@link ServiceLoader} - so the metering
 * strategy is a drop-in module (the in-memory token bucket; a coordinated limiter for a replicated deployment) and
 * the request filter names no implementation. Each provider reads its own configuration through the {@code config}
 * lookup (a property accessor returning {@code null} when unset). With no module installed, {@link #resolve}
 * answers {@link RateLimiter#NONE}: nothing is limited.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> {@link #name()} and {@link #requiredConfig()} are pure declarations callable from any
 *     thread; {@link #create} runs once, on the boot thread. The {@link RateLimiter} it returns is a shared singleton
 *     the request filter calls on <em>every</em> inbound request from every container thread, so <em>that</em> object
 *     must be thread-safe and cheap - its admission decision is on the hot path of the whole server.</li>
 * <li><b>Idempotency / replay.</b> {@link #create} is a pure factory: building a limiter starts no sweep and
 *     persists nothing, so resolving twice is safe. Admission itself is deliberately <em>not</em> idempotent - each
 *     call consumes budget - so a caller asks exactly once per request.</li>
 * <li><b>Absence sentinel.</b> The unselected absence of a limiter module is not an error: {@link #resolve} answers
 *     {@link RateLimiter#NONE} - never {@code null}, never an exception - which admits everything, the documented
 *     open default for a capability a deployment opts into. {@link #create} declines with an empty {@link Optional};
 *     {@code null} is never a legal return from it, from {@link #name()} or from {@link #requiredConfig()}.</li>
 * <li><b>Selection failure (&sect;9).</b> An <em>explicitly selected</em>
 *     {@code jenreg.rate-limiter=<name>} that no installed provider answers to, or whose provider
 *     declines, throws {@link IllegalStateException} at resolution naming the selection and the installed provider
 *     names - it does <em>not</em> resolve to {@link RateLimiter#NONE}. Silently admitting everything for an operator
 *     who asked for metering is the §9 defect exactly: the deployment looks protected and is not. An explicit
 *     selection outranks the {@code jenreg.<name>=false} toggle. Only an <em>unselected</em> deployment
 *     degrades to the sentinel.</li>
 * <li><b>Error visibility (&sect;9).</b> Nothing is swallowed at resolution: duplicate provider names, one provider
 *     registered twice, and more than one <em>enabled</em> limiter with no selection to disambiguate them all throw,
 *     naming the candidates and the setting that resolves them - which limiter meters a deployment is never decided
 *     by module-path order.</li>
 * <li><b>Lifecycle / ownership.</b> The composition owns the resolved limiter: {@link #resolve} builds at most one
 *     instance per call and hands it over, caching nothing and closing nothing. A limiter may own an eviction thread
 *     or a coordination client and closes them through its own lifecycle; the provider instance is created by
 *     {@link ServiceLoader}, consulted and discarded.</li>
 * <li><b>Ordering / determinism.</b> The resolved limiter is a function of the configuration and the installed
 *     providers only, never of discovery order.</li>
 * <li><b>Bounded work / cancellation.</b> {@link #create} does no I/O and no unbounded work. A limiter's own state
 *     must stay bounded under attacker-shaped key cardinality (per-credential or per-address buckets are evicted, not
 *     accumulated) so metering cannot become the memory leak it exists to prevent.</li>
 * </ol>
 */
public interface RateLimiterProvider {

    /** The limiter name this provider answers to, e.g. {@code token-bucket}. */
    String name();

    /** Build the limiter if the configuration enables it, reading settings through {@code config}; empty when off. */
    Optional<RateLimiter> create(UnaryOperator<String> config);

    /** The config keys this limiter cannot run without; empty (the default) for one that needs nothing. A provider
     *  whose required keys are unset {@link Features#active self-disables} at discovery. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /** The single enabled limiter discovered via {@link ServiceLoader}, resolved through the shared
     *  {@link Providers#optionalUnique} policy: an explicit {@code jenreg.rate-limiter=<name>} selects one
     *  by name and a selection nothing answers to <em>throws</em> rather than degrading (&sect;9), a
     *  {@code jenreg.<name>=false} switches one off, more than one enabled limiter is ambiguous rather
     *  than a discovery-order winner, and only an <em>unselected</em> deployment with no limiter installed resolves to
     *  {@link RateLimiter#NONE}. */
    static RateLimiter resolve(UnaryOperator<String> config) {
        return Providers.<RateLimiterProvider, RateLimiter>optionalUnique("rate-limiter",
                        ServiceLoader.load(RateLimiterProvider.class),
                        RateLimiterProvider::name,
                        Features.selection("rate-limiter"),
                        provider -> Features.active(provider.name(), provider.requiredConfig()),
                        provider -> provider.create(config))
                .orElse(RateLimiter.NONE);
    }
}
