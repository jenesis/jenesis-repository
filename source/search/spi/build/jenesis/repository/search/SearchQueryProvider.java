package build.jenesis.repository.search;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Providers;

/**
 * Discovers the free-text search index and binds it to a repository's scoped store, so the search surface reaches the
 * read model through {@code ServiceLoader} rather than a compile-time dependency on the optional index module. A
 * deployment that carries the {@code build.jenesis.repository.search.lucene} module provides one; a deployment without
 * it has none, so {@link #installed()} is empty and {@code /api/search} falls back to the built-in live substring
 * scan while the rest of the app runs.
 *
 * <p>A single provider instance serves every tenant and repository and may keep a per-scope in-memory searcher behind
 * the scenes, so a caller resolves the provider <em>once</em> (a final field) and calls {@link #over} per request: the
 * {@code scope} names the tenant/repository so the provider can cache and TTL-refresh that repository's searcher
 * across requests without re-loading the snapshot every time.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> One provider instance serves every tenant and repository: {@link #over} is called per
 *     request, concurrently, so the provider, its per-scope searcher cache and the {@link SearchQuery} it returns
 *     must all be thread-safe.</li>
 * <li><b>Absence sentinel.</b> {@link #installed()} answers an empty {@link Optional} when no index module is on
 *     the module path, and {@code /api/search} falls back to the built-in live substring scan. {@code null} is
 *     never a legal return from {@link #installed()}, from {@link #over}, or from either leg of the
 *     {@link SearchQuery} it hands back: a repository whose index has not been built - or whose stored index is a
 *     format the reader cannot open - answers an empty {@link Optional} from {@link SearchQuery#search} and
 *     {@link SearchQuery#licenses}, which is the signal to degrade to the live scan. That sentinel is
 *     <em>load-bearing</em> and distinct from an empty answer: a present-but-empty page means the index is usable
 *     and nothing matched, and rendering the two alike would serve a false-empty result (which is where the
 *     {@code null} this clause always forbade was finally removed from the query surface).</li>
 * <li><b>Bounded work (&sect;12).</b> {@link SearchQuery#search} answers one page of at most
 *     {@link SearchQuery#MAX_PAGE} rows, whatever limit a caller asks for, and the visible outcome at that bound is
 *     {@link SearchQuery.Hits#nextCursor()}: an implementation may never return a clamped list that reads like a
 *     complete one. The ceiling lives here rather than in an implementation so a second index cannot honour a
 *     different one - or none.</li>
 * <li><b>Selection failure (&sect;9).</b> This SPI has <em>no</em> selection key - nothing names an index by name -
 *     so there is no explicitly-selected miss to fail on. The one resolution failure is ambiguity: two installed
 *     providers would make module-path order decide which index answers a query, so {@link #installed()}
 *     <em>throws</em> naming both rather than picking a discovery-order winner. Resolution runs through the shared
 *     {@link Providers#optionalUnique} primitive, never a hand-rolled loop.</li>
 * <li><b>Tenant scoping (&sect;6).</b> The caller hands in an already-scoped store and a {@code scope} key that
 *     names the tenant/repository the searcher is cached under; a query may never read across that scope, so the
 *     cache key must include the tenant.</li>
 * <li><b>Read purity (&sect;10).</b> A query renders the persisted index snapshot only - it never indexes, writes
 *     or fetches on the read path. Indexing is the sweep's job.</li>
 * <li><b>Staleness.</b> A snapshot-backed searcher is by construction behind the store; the surface shows when the
 *     index was last built rather than presenting an empty result as an authoritative "no matches".</li>
 * <li><b>Lifecycle / ownership.</b> The caller resolves the provider <em>once</em> (a final field) and calls
 *     {@link #over} per request; the provider owns and closes whatever per-scope readers it caches, and
 *     {@link #installed()} caches nothing and closes nothing.</li>
 * <li><b>Ordering / determinism.</b> Which provider {@link #installed()} answers is a function of what is
 *     installed, never of discovery order.</li>
 * </ol>
 */
public interface SearchQueryProvider {

    /** Bind the search read model to one repository's scoped store; {@code scope} is a stable per-repository key
     *  (e.g. {@code tenant/repository}) the provider may cache a searcher under. */
    SearchQuery over(ArtifactStore store, String scope);

    /** The installed provider discovered via {@link ServiceLoader}, resolved through the shared
     *  {@link Providers#optionalUnique} policy: empty when the index module is absent, and a <em>second</em>
     *  installed provider throws rather than letting module-path order decide which index answers. */
    static Optional<SearchQueryProvider> installed() {
        return Providers.singleton("search", ServiceLoader.load(SearchQueryProvider.class));
    }
}
