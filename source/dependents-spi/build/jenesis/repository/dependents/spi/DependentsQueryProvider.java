package build.jenesis.repository.dependents.spi;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Providers;

/**
 * Discovers the reverse-dependency index and binds it to a repository's scoped store, so the query surface reaches
 * the read model through {@code ServiceLoader} rather than a compile-time dependency on the optional index module.
 * A deployment that carries the {@code build.jenesis.repository.dependents} module provides one; a deployment
 * without it has none, so {@link #installed()} is empty and the query degrades - the {@code /api/dependents}
 * endpoint answers {@code 501} and the console hides the panel - while the rest of the app runs. The provider is
 * stateless: it takes the per-request scoped store on each call, so a single instance serves every tenant and
 * repository.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> One provider instance serves the whole deployment: {@link #over} is called per request,
 *     concurrently from every request thread, and both the provider and the {@link DependentsQuery} it returns must
 *     be thread-safe. The provider holds no per-call state - the scoped store is the only per-call input.</li>
 * <li><b>Absence sentinel.</b> {@link #installed()} answers an empty {@link Optional} when no index module is on the
 *     module path; that is the capability signal the endpoint answers {@code 501} on and the console hides the panel
 *     for. {@code null} is never a legal return from {@link #installed()} or {@link #over}.</li>
 * <li><b>Selection failure (&sect;9).</b> This SPI has <em>no</em> selection key: nothing names an index by name, so
 *     there is no explicitly-selected miss to fail on. The one resolution failure is ambiguity - two installed
 *     providers make module-path order decide which read model the query surface serves, so
 *     {@link #installed()} <em>throws</em> naming both rather than picking a discovery-order winner. Resolution runs
 *     through the shared {@link Providers#optionalUnique} primitive, never a hand-rolled loop.</li>
 * <li><b>Tenant scoping (&sect;6).</b> The provider never resolves a tenant: the caller hands in an
 *     already-scoped store and the query may read nothing outside it.</li>
 * <li><b>Read purity (&sect;10).</b> {@link #over} and the query it returns render stored index state only - no
 *     external fetch, no index build on the read path. An index that has never been built answers empty rather
 *     than building itself.</li>
 * <li><b>Lifecycle / ownership.</b> The caller resolves the provider once (a final field) and calls {@link #over}
 *     per request. The provider is created by {@link ServiceLoader} and may cache per-scope readers it owns and
 *     closes itself; {@link #installed()} caches nothing and closes nothing.</li>
 * <li><b>Ordering / determinism.</b> Which provider {@link #installed()} answers is a function of what is installed,
 *     never of discovery order.</li>
 * <li><b>Bounded work / cancellation.</b> A query this provider returns must answer
 *     {@link DependentsQuery#coordinates(String, int)} without materialising the reverse-dependency key set, and
 *     {@link DependentsQuery#built()} with a single small-object read of the sweep's completion marker - never a
 *     walk of the index, which cannot answer the question anyway. The inherited page default is a small-index
 *     fallback whose bound is <em>visible</em>: it refuses past {@link ArtifactStore#MAX_INHERITED_CHILDREN}
 *     coordinates with an {@link IllegalStateException} naming the class and the override, rather than sorting and
 *     slicing the whole graph once per page.</li>
 * </ol>
 */
public interface DependentsQueryProvider {

    /** Bind the reverse-dependency read model to one repository's scoped store. */
    DependentsQuery over(ArtifactStore store);

    /** The installed provider discovered via {@link ServiceLoader}, resolved through the shared
     *  {@link Providers#optionalUnique} policy: empty when the index module is absent, and a <em>second</em>
     *  installed provider throws rather than letting module-path order decide which read model answers. */
    static Optional<DependentsQueryProvider> installed() {
        return Providers.singleton("dependents", ServiceLoader.load(DependentsQueryProvider.class));
    }
}
