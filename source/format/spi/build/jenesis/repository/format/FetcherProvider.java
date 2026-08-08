package build.jenesis.repository.format;

import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Providers;

import module java.base;

/**
 * A named factory for the upstream {@link ProxyFormat.Fetcher}, discovered at runtime with {@link ServiceLoader} -
 * so the machinery that talks to upstream registries (the HTTP client, index revalidation, negative caching) is a
 * drop-in module, and the dispatcher names no transport. Each provider reads its own configuration through the
 * {@code config} lookup (a property accessor returning {@code null} when unset), staying free of any framework
 * dependency. With no provider installed {@link #resolve} answers {@link ProxyFormat.Fetcher#NONE}: the deployment
 * serves local content only - a proxy upstream is never consulted and an import is refused - which a caller detects
 * by identity rather than by a failing fetch.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> {@link #name()} and {@link #requiredConfig()} are pure declarations callable from any
 *     thread; {@link #create} runs once, on the boot thread. The {@link ProxyFormat.Fetcher} it returns is a shared
 *     singleton every proxying request thread calls concurrently, so <em>that</em> object must be thread-safe.</li>
 * <li><b>Idempotency / replay.</b> {@link #create} is a pure factory: building a fetcher opens no upstream
 *     connection and performs no request, so resolving twice is safe. A repeated {@code fetch} of the same URL is a
 *     plain re-read of upstream state and mutates nothing locally.</li>
 * <li><b>Absence sentinel.</b> The unselected absence of a fetcher module is not an error: {@link #resolve} answers
 *     {@link ProxyFormat.Fetcher#NONE} - never {@code null}, never an exception - which a caller detects by identity
 *     rather than by a failing fetch. The deployment then serves local content only: no upstream is consulted and an
 *     import is refused. {@link #create} declines with an empty {@link Optional}; {@code null} is never a legal
 *     return from it, from {@link #name()} or from {@link #requiredConfig()}.</li>
 * <li><b>Selection failure (&sect;9).</b> An <em>explicitly selected</em> {@code jenesis.repository.fetcher=<name>}
 *     that no installed provider answers to, or whose provider declines, throws {@link IllegalStateException} at
 *     resolution naming the selection and the installed provider names - it does <em>not</em> resolve to
 *     {@link ProxyFormat.Fetcher#NONE}. An operator who named a transport and silently got none would see every
 *     proxy route answer 404 as if upstream held nothing. An explicit selection outranks the
 *     {@code jenesis.repository.<name>=false} toggle. Only an <em>unselected</em> deployment degrades to the
 *     sentinel.</li>
 * <li><b>Streaming (&sect;1).</b> The fetcher is on the artifact download path: a proxied artifact must be streamed
 *     through to the caller and the store, never fully materialised. Only small index/metadata documents may be read
 *     whole.</li>
 * <li><b>Error visibility (&sect;9).</b> Nothing is swallowed at resolution: duplicate provider names, one provider
 *     registered twice, and more than one <em>enabled</em> fetcher with no selection to disambiguate them all throw,
 *     naming the candidates and the setting that resolves them - the transport a deployment proxies through is never
 *     decided by module-path order. On the fetch path an upstream failure is reported to the caller as a failure; it
 *     is never turned into an empty-but-successful answer, which would look like "upstream does not have it".</li>
 * <li><b>Read purity (&sect;10).</b> {@link #name()} and {@link #requiredConfig()} are pure declarations - no
 *     network, no store, no lazy initialisation. Network I/O belongs to the returned fetcher's own {@code fetch},
 *     which a read path only reaches on an explicit proxy miss.</li>
 * <li><b>Lifecycle / ownership.</b> The composition owns the resolved fetcher: {@link #resolve} builds at most one
 *     instance per call and hands it over, caching nothing and closing nothing. A fetcher may own an HTTP client,
 *     connection pool or cache and closes them through its own lifecycle; the provider instance is created by
 *     {@link ServiceLoader}, consulted and discarded, and must hold no state a later call depends on.</li>
 * <li><b>Ordering / determinism.</b> The resolved fetcher is a function of the configuration and the installed
 *     providers only, never of discovery order.</li>
 * <li><b>Bounded work / cancellation.</b> {@link #create} does no I/O and no unbounded work. Connect/read timeouts,
 *     redirect limits, response-size caps and the SSRF posture that keeps an upstream URL off private address space
 *     are the returned fetcher's own bounds, and reaching one surfaces as a named failure rather than a truncated
 *     body presented as complete.</li>
 * </ol>
 */
public interface FetcherProvider {

    /** The fetcher name this provider answers to, e.g. {@code http}. */
    String name();

    /** Build the fetcher if the configuration enables it, reading settings through {@code config}; empty when off. */
    Optional<ProxyFormat.Fetcher> create(UnaryOperator<String> config);

    /** The config keys this fetcher cannot run without; empty (the default) for one that needs nothing. A provider
     *  whose required keys are unset {@link Features#active self-disables} at discovery. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /** The single enabled fetcher discovered via {@link ServiceLoader}, resolved through the shared
     *  {@link Providers#optionalUnique} policy: an explicit {@code jenesis.repository.fetcher=<name>} selects one by
     *  name and a selection nothing answers to <em>throws</em> rather than degrading (&sect;9), a
     *  {@code jenesis.repository.<name>=false} switches one off, more than one enabled fetcher is ambiguous rather
     *  than a discovery-order winner, and only an <em>unselected</em> deployment with no fetcher installed resolves to
     *  {@link ProxyFormat.Fetcher#NONE}. */
    static ProxyFormat.Fetcher resolve(UnaryOperator<String> config) {
        return Providers.<FetcherProvider, ProxyFormat.Fetcher>optionalUnique("fetcher",
                        ServiceLoader.load(FetcherProvider.class),
                        FetcherProvider::name,
                        Features.selection("fetcher"),
                        provider -> Features.active(provider.name(), provider.requiredConfig()),
                        provider -> provider.create(config))
                .orElse(ProxyFormat.Fetcher.NONE);
    }
}
