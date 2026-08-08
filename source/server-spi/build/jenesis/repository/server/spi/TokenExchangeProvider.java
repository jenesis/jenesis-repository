package build.jenesis.repository.server.spi;

import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Providers;

import module java.base;

/**
 * A named factory for a {@link TokenExchange}, discovered at runtime with {@link ServiceLoader} - so the machinery
 * that validates workload identity tokens (OIDC discovery, JWKS signature checks) is a drop-in module with its own
 * dependencies, and the composition names no protocol. Each provider reads its own configuration through the
 * {@code config} lookup (a property accessor returning {@code null} when unset). With no module installed,
 * {@link #resolve} answers {@link TokenExchange#NONE}: the exchange endpoint says the feature is not installed.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> {@link #name()} and {@link #requiredConfig()} are pure declarations callable from any
 *     thread; {@link #create} runs once, on the boot thread. The {@link TokenExchange} it returns is a shared
 *     singleton the exchange endpoint calls concurrently, so <em>that</em> object must be thread-safe, including any
 *     key material or discovery document it caches.</li>
 * <li><b>Idempotency / replay.</b> {@link #create} is a pure factory: building an exchange mints no credential and
 *     persists nothing, so resolving twice is safe. An exchange call itself mints a credential and is deliberately
 *     not idempotent; replay protection (nonce, {@code jti}, expiry) is the exchange's own business.</li>
 * <li><b>Absence sentinel.</b> The unselected absence of an exchange module is not an error: {@link #resolve} answers
 *     {@link TokenExchange#NONE} - never {@code null}, never an exception - and the exchange endpoint reports the
 *     feature as not installed rather than failing obscurely. {@link TokenExchange#NONE} exchanges nothing, so
 *     absence is fail-closed. {@link #create} declines with an empty {@link Optional}; {@code null} is never a legal
 *     return from it, from {@link #name()} or from {@link #requiredConfig()}.</li>
 * <li><b>Selection failure (&sect;9).</b> An <em>explicitly selected</em>
 *     {@code jenesis.repository.token-exchange=<name>} that no installed provider answers to, or whose provider
 *     declines, throws {@link IllegalStateException} at resolution naming the selection and the installed provider
 *     names - it does <em>not</em> resolve to {@link TokenExchange#NONE}. A deployment that configured workload
 *     identity and silently got none would have every CI job fall back to a long-lived static credential. An explicit
 *     selection outranks the {@code jenesis.repository.<name>=false} toggle. Only an <em>unselected</em> deployment
 *     degrades to the sentinel.</li>
 * <li><b>Error visibility (&sect;9).</b> Nothing is swallowed at resolution: duplicate provider names, one provider
 *     registered twice, and more than one <em>enabled</em> exchange with no selection to disambiguate them all throw,
 *     naming the candidates and the setting that resolves them - which protocol admits a workload token is never
 *     decided by module-path order. On the exchange path a validation failure is refused, never downgraded to an
 *     anonymous or partially-trusted identity.</li>
 * <li><b>Lifecycle / ownership.</b> The composition owns the resolved exchange: {@link #resolve} builds at most one
 *     instance per call over the deployment's {@link Authorization} and hands it over, caching nothing and closing
 *     nothing. An exchange may own an HTTP client and a JWKS cache and closes them through its own lifecycle; the
 *     provider instance is created by {@link ServiceLoader}, consulted and discarded.</li>
 * <li><b>Ordering / determinism.</b> The resolved exchange is a function of the configuration and the installed
 *     providers only, never of discovery order.</li>
 * <li><b>Bounded work / cancellation.</b> {@link #create} does no network I/O - OIDC discovery and JWKS fetching
 *     belong to the exchange's own bounded, timeout-guarded refresh, never to boot-time provider construction, so a
 *     down identity provider cannot stop the server from starting.</li>
 * </ol>
 */
public interface TokenExchangeProvider {

    /** The exchange name this provider answers to, e.g. {@code oidc}. */
    String name();

    /** Build the exchange over the deployment's {@link Authorization} (whose trust policy admits tokens), reading
     *  settings through {@code config}; empty when off. */
    Optional<TokenExchange> create(Authorization authorization, UnaryOperator<String> config);

    /** The config keys this exchange cannot run without; empty (the default) for one that needs nothing. A provider
     *  whose required keys are unset {@link Features#active self-disables} at discovery. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /** The single enabled exchange discovered via {@link ServiceLoader}, resolved through the shared
     *  {@link Providers#optionalUnique} policy: an explicit {@code jenesis.repository.token-exchange=<name>} selects
     *  one by name and a selection nothing answers to <em>throws</em> rather than degrading (&sect;9), a
     *  {@code jenesis.repository.<name>=false} switches one off, more than one enabled exchange is ambiguous rather
     *  than a discovery-order winner, and only an <em>unselected</em> deployment with no exchange installed resolves
     *  to {@link TokenExchange#NONE}. */
    static TokenExchange resolve(Authorization authorization, UnaryOperator<String> config) {
        return Providers.<TokenExchangeProvider, TokenExchange>optionalUnique("token-exchange",
                        ServiceLoader.load(TokenExchangeProvider.class),
                        TokenExchangeProvider::name,
                        Features.selection("token-exchange"),
                        provider -> Features.active(provider.name(), provider.requiredConfig()),
                        provider -> provider.create(authorization, config))
                .orElse(TokenExchange.NONE);
    }
}
