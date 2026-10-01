package build.jenesis.repository.search;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Providers;

/**
 * Discovers the full-text index and binds it to a repository's scoped store, so search reaches it without depending on
 * the module that builds it ({@code build.jenesis.repository.search.lucene} in the core). Without one
 * {@link #installed()} is empty and every repository answers by name, whatever its {@link SearchMode}.
 *
 * <p>One instance serves every tenant and repository and may cache a per-scope searcher, so a caller resolves the
 * provider once and calls {@link #over} per request; {@code scope} names the tenant and repository the searcher is
 * cached and refreshed under.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> {@link #over} is called per request, concurrently, so the provider, its searcher cache
 *       and the returned {@link SearchQuery} are thread-safe.</li>
 *   <li><b>Absence sentinel.</b> {@link #installed()} answers an empty {@link Optional} without an index module.
 *       {@code null} is never returned from {@link #installed()}, {@link #over} or {@link SearchQuery#search}; an index
 *       not yet built or in an unreadable format answers an empty {@link Optional} from {@link SearchQuery#search} -
 *       the signal to answer by name, distinct from a present empty page, which means nothing matched.</li>
 *   <li><b>Bounded work.</b> {@link SearchQuery#search} answers at most {@link SearchQuery#MAX_PAGE} rows, the
 *       remainder named by {@link SearchQuery.Hits#nextCursor()}; the ceiling lives in the contract so no index honours
 *       another.</li>
 *   <li><b>Selection failure.</b> No key names an index, so the one failure is ambiguity: two installed providers make
 *       {@link #installed()} throw naming both, through the shared {@link Providers#singleton}.</li>
 *   <li><b>Tenant scoping.</b> The caller hands in an already-scoped store and a {@code scope} key; a query never reads
 *       across it, so the cache key includes the tenant.</li>
 *   <li><b>Read purity.</b> A query renders the persisted snapshot only - no indexing, writing or fetching on the read
 *       path.</li>
 *   <li><b>Staleness.</b> A snapshot-backed searcher is behind the store by construction; the surface shows when the
 *       index was last built.</li>
 *   <li><b>Lifecycle / ownership.</b> The caller resolves the provider once and calls {@link #over} per request; the
 *       provider owns and closes the readers it caches, and {@link #installed()} caches and closes nothing.</li>
 *   <li><b>Ordering / determinism.</b> Which provider answers depends on what is installed, never on discovery
 *       order.</li>
 * </ol>
 */
public interface SearchQueryProvider {

    /** Bind the search read model to one repository's scoped store; {@code scope} is a stable per-repository key
     *  ({@code tenant/repository}) the provider may cache a searcher under. */
    SearchQuery over(ArtifactStore store, String scope);

    /** The installed provider, through the shared {@link Providers#singleton}: empty without the index module, and a
     *  second installed provider throws rather than letting module-path order decide which index answers. */
    static Optional<SearchQueryProvider> installed() {
        return Providers.singleton("search", ServiceLoader.load(SearchQueryProvider.class));
    }
}
