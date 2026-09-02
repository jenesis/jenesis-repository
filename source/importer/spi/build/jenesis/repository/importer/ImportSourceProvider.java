package build.jenesis.repository.importer;

import module java.base;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.icon.IconContributor;

/**
 * Discovers and builds an {@link ImportSource} for a named incumbent. A source implementation ships as its own module
 * that provides one of these; the server loads every provider with {@link java.util.ServiceLoader} and, for a
 * submitted migration, asks each whether it {@link #handles handles} the requested source, then has the first match
 * {@link #create build} the source from the request and the fetcher the server supplies. So the server carries no
 * knowledge of Nexus or Artifactory (or any future incumbent) - it only knows this contract, and a new one plugs in
 * by adding a module.
 *
 * <h2>Contract</h2>
 * {@code ImportContract} in the importer testkit ({@code source/importer/testkit}) proves clauses 2, 3, 4, 5, 7 and 12
 * over every discovered provider through a per-connector fixture, and its census fails on a provider with neither.
 * <ol>
 * <li><b>Thread-safety.</b> The provider is a discovery singleton the server holds for the process's life and may call
 *     from several request threads at once, so {@link #name}, {@link #label}, {@link #handles},
 *     {@link #requiresFormat}, {@link #requiredConfig} and {@link #create} are all safe to call concurrently. The
 *     {@link ImportSource} {@link #create} returns is per-migration and need not be.</li>
 * <li><b>Idempotency / replay.</b> {@link #create} is a pure construction: the same request builds an equivalent
 *     source, and building one starts no walk, opens no connection and mutates nothing. A migration is resumed by
 *     building a fresh source from the same request carrying {@link ImportRequest#cursor() the checkpointed cursor} -
 *     the walk then continues rather than re-delivering what the interrupted run had already fully consumed.</li>
 * <li><b>Absence sentinel.</b> {@link #create} answers {@code null} - not an exception, and not a half-built source
 *     that fails later - when the request is missing something this source needs (an ecosystem format it declared
 *     through {@link #requiresFormat}, a root that does not answer at all). The caller reports that as a bad request.
 *     Every other accessor answers a value; {@link #requiredConfig} answers an empty set, never {@code null}.</li>
 * <li><b>Selection failure (&sect;9).</b> Providers are additive ({@code ALL}), so there is nothing to select among
 *     them: a submitted source name reaches the first provider that {@link #handles} it and an unhandled name is a bad
 *     request. A provider whose {@link #requiredConfig} keys are unset <em>self-disables at discovery</em>
 *     ({@code Features.active}) rather than being discovered and failing per migration, so an operator sees one line at
 *     boot naming the missing keys instead of a runtime failure per submission.</li>
 * <li><b>Streaming (&sect;1).</b> The source this builds streams every asset: bytes go from the incumbent to storage
 *     through {@link ImportSource.Content#open} without being materialised, and the credentials wrapper a provider puts
 *     around the fetcher must not turn a streaming {@code download} into a buffered {@code fetch}.</li>
 * <li><b>Tenant scoping (&sect;6).</b> A provider is deployment-wide and holds no tenant state; the tenant rides the
 *     store the write half of the migration is given, never the request or the source.</li>
 * <li><b>Error visibility (&sect;9).</b> Nothing is swallowed. An incumbent that refuses, is absent or cannot answer
 *     surfaces from the walk as an {@link ImportFailure} carrying its {@link ImportFailure.Kind}, so a job can tell a
 *     bad credential from a throttle rather than string-matching one {@code IOException}.</li>
 * <li><b>Read purity (&sect;10).</b> {@link #create} may probe the root to decide whether to build a source at all
 *     (that probe is what makes a typo'd URL a synchronous bad request), but it writes nothing and imports nothing;
 *     every asset read happens inside the walk.</li>
 * <li><b>Staleness.</b> An import is a point-in-time walk with no cached view of its own; the cursor a walk checkpoints
 *     is how far it got, not how fresh it is.</li>
 * <li><b>Lifecycle / ownership.</b> The server discovers providers once with {@code ServiceLoader} and keeps them; a
 *     provider owns no threads and no HTTP client - it is handed the shared {@link ProxyFormat.Fetcher} and must use
 *     it (wrapping it to add credentials is allowed, replacing it is not). That fetcher is <em>already screened</em>
 *     ({@link ImportScreen}, applied by {@link #open}), which is why a connector carries no URL screen of its own: the
 *     URLs a listing hands back are judged at the fetch, once, for every connector. The {@link ImportSource} is owned
 *     by the migration that requested it and is not reused across migrations.</li>
 * <li><b>Ordering / concurrency.</b> {@link #name} is unique across installed providers and stable across releases (it
 *     is what an operator writes and what a cursor was issued under); discovery order must not decide which provider
 *     answers a source name.</li>
 * <li><b>Bounded work / cancellation.</b> {@link #create} does at most one bounded probe. The walk itself is bounded by
 *     the source's own paging and depth caps, and reaching one is an explicit {@link ImportFailure}, never a silently
 *     truncated asset list - a migration that quietly stopped half way looks exactly like a complete one.</li>
 * </ol>
 */
public interface ImportSourceProvider extends IconContributor {

    /** The stable source name this provider answers to (for example {@code "nexus"}, {@code "artifactory"}), so a
     *  console or client can enumerate the installed sources instead of hardcoding them. */
    String name();

    /** A human-readable label for pickers; the {@link #name() name} unless the provider overrides it. */
    default String label() {
        return name();
    }

    /** Whether a migration from this source must name an ecosystem format up front - a single-package-type
     *  incumbent (Artifactory, say) needs one, while a source that reports a format per asset does not. */
    default boolean requiresFormat() {
        return false;
    }

    /** Whether this provider builds sources for the given source name. */
    default boolean handles(String source) {
        return name().equals(source);
    }

    /** The config keys this source cannot run without; empty (the default) for one that takes everything from the
     *  {@link ImportRequest}. A provider whose required keys are unset self-disables at discovery
     *  ({@code build.jenesis.repository.store.Features}). */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /** Build the source from the request, streaming through {@code fetcher}, or null when the request is missing
     *  something this source needs (an Artifactory source without an ecosystem format, say) - which the caller reports
     *  as a bad request. An <em>edge</em> calls {@link #open} rather than this, so the fetcher a connector receives is
     *  screened; this is the seam a connector implements, not the one a caller reaches for. */
    ImportSource create(ImportRequest request, ProxyFormat.Fetcher fetcher);

    /**
     * Build the source an edge will walk: {@link #create}, with the fetcher screened against the URL the operator
     * submitted ({@link ImportScreen#around}). Every import edge builds its source through here, so a URL a source
     * hands back - a Nexus listing's per-asset {@code downloadUrl}, an index's absolute coordinate URL - is judged
     * before it is fetched, whichever connector answered and whether or not that connector knows the screen exists.
     * Calling {@link #create} directly hands out an unscreened transport and is not how an edge builds a source.
     */
    static ImportSource open(ImportSourceProvider provider, ImportRequest request, ProxyFormat.Fetcher fetcher) {
        return provider.create(request, ImportScreen.around(fetcher, request.url()));
    }
}
