package build.jenesis.repository.importer;

import module java.base;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.icon.IconContributor;

/**
 * Discovers and builds an {@link ImportSource} for a named incumbent. A connector module provides one; the server loads
 * every provider with {@link java.util.ServiceLoader}, asks which {@link #handles handles} a submitted source, and has
 * it {@link #create build} the source from the request and the server's fetcher, so the server knows no incumbent.
 *
 * <h2>Contract</h2>
 * {@code ImportContract} proves clauses 2, 3, 4, 5, 7 and 12 over every discovered provider through a per-connector
 * fixture, and its census fails on a provider with none.
 * <ol>
 *   <li><b>Thread-safety.</b> A discovery singleton called from several request threads, so every method is safe
 *       concurrently; the {@link ImportSource} {@link #create} returns is per-migration and need not be.</li>
 *   <li><b>Idempotency / replay.</b> {@link #create} is pure construction: it starts no walk, opens no connection and
 *       mutates nothing. A migration resumes from a fresh source built with
 *       {@link ImportRequest#cursor() the checkpointed cursor}.</li>
 *   <li><b>Absence sentinel.</b> {@link #create} answers {@code null}, never an exception or a half-built source, when
 *       the request lacks what the source needs (a format {@link #requiresFormat} declared, a root that does not
 *       answer); the caller reports a bad request. {@link #requiredConfig} answers an empty set, never
 *       {@code null}.</li>
 *   <li><b>Selection failure.</b> Providers are additive ({@code ALL}): a source name reaches the first provider that
 *       {@link #handles} it, and an unhandled name is a bad request. A provider with unset {@link #requiredConfig}
 *       self-disables at discovery ({@code Features.active}), one boot line naming the keys.</li>
 *   <li><b>Streaming.</b> Assets stream from the incumbent to storage through {@link ImportSource.Content#open}, and a
 *       credentials wrapper must not turn a streaming {@code download} into a buffered {@code fetch}.</li>
 *   <li><b>Tenant scoping.</b> A provider is deployment-wide; the tenant rides the store the write half is given.</li>
 *   <li><b>Error visibility.</b> An incumbent that refuses, is absent or cannot answer surfaces from the walk as an
 *       {@link ImportFailure} of its {@link ImportFailure.Kind}.</li>
 *   <li><b>Read purity.</b> {@link #create} may probe the root, so a mistyped URL is a synchronous bad request, but
 *       writes and imports nothing; every asset read happens in the walk.</li>
 *   <li><b>Staleness.</b> An import is a point-in-time walk with no cached view; a cursor says how far it got.</li>
 *   <li><b>Lifecycle / ownership.</b> Providers are discovered once and kept; a provider owns no thread or HTTP client
 *       and must use the shared {@link ProxyFormat.Fetcher} it is handed, which it may wrap for credentials but not
 *       replace. That fetcher is already screened ({@link ImportScreen}, applied by {@link #open}), so a connector
 *       carries no URL screen. The {@link ImportSource} belongs to one migration.</li>
 *   <li><b>Ordering / concurrency.</b> {@link #name} is unique and stable, being what an operator writes and a cursor
 *       was issued under; discovery order never decides which provider answers.</li>
 *   <li><b>Bounded work / cancellation.</b> {@link #create} makes at most one bounded probe; the walk is bounded by its
 *       paging and depth caps, and reaching one is an explicit {@link ImportFailure}, never a silently truncated
 *       list.</li>
 * </ol>
 */
public interface ImportSourceProvider extends IconContributor {

    /** The stable source name this provider answers to (such as {@code "nexus"}), so a client can list the installed
     *  sources. */
    String name();

    /** A human-readable label for pickers; the {@link #name() name} unless the provider overrides it. */
    default String label() {
        return name();
    }

    /** Whether a migration from this source must name an ecosystem format: a single-type incumbent needs one, a source
     *  reporting a format per asset does not. */
    default boolean requiresFormat() {
        return false;
    }

    /** Whether this provider builds sources for the given source name. */
    default boolean handles(String source) {
        return name().equals(source);
    }

    /** The config keys this source cannot run without; empty by default. Unset, the provider self-disables at
     *  discovery. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /** Build the source from the request, streaming through {@code fetcher}, or null when the request lacks something
     *  it needs, reported as a bad request. An edge calls {@link #open}, which screens the fetcher; this is the seam a
     *  connector implements. */
    ImportSource create(ImportRequest request, ProxyFormat.Fetcher fetcher);

    /** Build the source an edge walks: {@link #create} with the fetcher screened against the submitted URL
     *  ({@link ImportScreen#around}), so every URL a source hands back is judged before it is fetched, whichever
     *  connector answered. */
    static ImportSource open(ImportSourceProvider provider, ImportRequest request, ProxyFormat.Fetcher fetcher) {
        return provider.create(request, ImportScreen.around(fetcher, request.url()));
    }

    /** Every import source on the module path, whatever its toggle: validated once, name-ordered, one set per process.
     *  Only this module loads the service. */
    static List<ImportSourceProvider> declared() {
        return ImportSources.DECLARED;
    }

    /** Every {@link Features#active switched-on, fully configured} source in the deployment's configuration: the set
     *  every edge builds from, so a connector configured off is unreachable on all of them alike. */
    static List<ImportSourceProvider> installed() {
        return installed(Features.settings());
    }

    /** As {@link #installed()}, against a lookup the caller supplies, keyed by bare feature names. */
    static List<ImportSourceProvider> installed(UnaryOperator<String> config) {
        List<ImportSourceProvider> active = new ArrayList<>();
        for (ImportSourceProvider provider : declared()) {
            if (Features.active(config, provider.name(), provider.requiredConfig())) {
                active.add(provider);
            }
        }
        return List.copyOf(active);
    }

    /** The installed source that {@link #handles handles} {@code source}, or empty. */
    static Optional<ImportSourceProvider> installed(String source, UnaryOperator<String> config) {
        for (ImportSourceProvider provider : installed(config)) {
            if (provider.handles(source)) {
                return Optional.of(provider);
            }
        }
        return Optional.empty();
    }
}
