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
 *     whole. The three {@link ProxyFormat.Fetcher} overloads are not interchangeable, and a real transport
 *     <b>overrides both defaults</b>: {@link ProxyFormat.Fetcher#download} must open the response body as a stream
 *     rather than materialising it (the SPI default builds one from the buffered {@link ProxyFormat.Fetcher#fetch},
 *     which is exactly the whole-artifact-in-heap this clause forbids), and {@link ProxyFormat.Fetcher#head} must
 *     issue a real HTTP {@code HEAD} (the SPI default falls back to {@code download}, so it opens - though never
 *     reads - the body of an artifact whose size was all the caller wanted). The defaults exist so a test double or a
 *     degenerate fetcher such as {@link ProxyFormat.Fetcher#NONE} need not implement three methods; a provider that
 *     ships them to production has not met this clause. The buffered {@code fetch} is the small-document path and
 *     carries a ceiling of its own (clause 10).</li>
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
 * <li><b>Bounded work / cancellation.</b> {@link #create} does no I/O and no unbounded work. The bounds that matter
 *     are the returned fetcher's, and each has a named outcome rather than a quiet one:
 *     <ul>
 *       <li>a <b>connect timeout</b> and a <b>per-request timeout</b>, so an upstream that accepts a connection and
 *           then says nothing cannot hang a proxy read or an import forever. A timeout is the contract's transport
 *           failure - an empty {@link Optional} - so the proxy lets the local {@code 404} stand and an import is
 *           refused, rather than a {@code 5xx} escaping;</li>
 *       <li>a <b>response-size cap on the buffered {@link ProxyFormat.Fetcher#fetch}</b> only. That leg materialises
 *           the body, so a hostile or compromised upstream answering a multi-gigabyte "index" would otherwise exhaust
 *           the heap before anything downstream could cap it; over the cap the read fails by name. The streaming
 *           {@link ProxyFormat.Fetcher#download} leg is deliberately uncapped - it copies network-to-store without
 *           buffering, and a size limit there would refuse legitimately large artifacts;</li>
 *       <li>a <b>bounded redirect chain</b> (clause 11).</li>
 *     </ul>
 *     A body that ends short of its declared length surfaces as an {@link java.io.IOException} on the read, so a
 *     truncated response is never stored as a complete cached artifact.</li>
 * <li><b>Redirect policy.</b> Redirects are followed by the fetcher <em>by hand</em>, never by an HTTP client's
 *     automatic policy, because the automatic policies re-send every request header - {@code Authorization} included -
 *     to the redirect target even across a change of host, and both callers legitimately redirect off-origin (a proxy
 *     fetch to a CDN, an import download to a presigned object-store URL). The rules are therefore:
 *     <ul>
 *       <li><b>the chain is bounded</b>, so a redirect loop cannot spin a fetch forever;</li>
 *       <li><b>a hop that leaves the original origin drops the credential-bearing headers</b> ({@code Authorization},
 *           {@code Proxy-Authorization}, {@code Cookie}, and the repository's own key header) the way a browser or a
 *           container client does; a same-origin hop keeps them. Origin is scheme, host and effective port;</li>
 *       <li><b>the method is carried unchanged</b> across every hop, so a redirected {@code HEAD} stays a
 *           {@code HEAD} and never becomes a body transfer;</li>
 *       <li>an intermediate response's body is closed before the next hop, and only the final response is handed to
 *           the caller with its body intact.</li>
 *     </ul></li>
 * <li><b>SSRF posture.</b> The fetch target is chosen partly by parties that are not the operator, so the screen is
 *     split by <em>who chose the URL</em>, and the split is the contract:
 *     <ul>
 *       <li><b>every redirect target is the fetcher's to screen</b>, because the upstream chose it. Each hop's host is
 *           re-judged against the shared {@link PrivateHosts} classifier before it is followed, and a hop to a
 *           private, loopback, link-local, site-local, CGNAT, multicast, IPv6 unique-local or cloud-metadata host is
 *           refused with an {@link java.io.IOException} - the request never reaches that host and the caller sees a
 *           visible failure, never a silently proxied internal response. Screening the operator-supplied URL alone
 *           would be worthless: a public URL that {@code 30x}es onward to {@code 169.254.169.254} is the whole
 *           attack;</li>
 *       <li><b>the initial URL is not re-judged here</b>, deliberately. A proxy upstream is operator-configured, and
 *           an import root is screened at the trigger that accepted it - so re-screening would refuse a deployment's
 *           own intentionally-internal upstream. A caller that composes an initial URL out of <em>foreign</em> input
 *           (an absolute download URL taken off an upstream index) owns that screen and applies the same
 *           {@link PrivateHosts} classifier itself before handing the URL over;</li>
 *       <li>a host that does not resolve is <b>not</b> refused - it is unreachable, so it is no vector, and masking it
 *           would turn an honest "no such host" into a security error. DNS rebinding is explicitly out of scope: the
 *           connection races the record, which is why this screen is one of a deployment's defences and not the
 *           only one.</li>
 *     </ul></li>
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
