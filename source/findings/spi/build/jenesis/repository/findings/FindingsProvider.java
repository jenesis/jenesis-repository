package build.jenesis.repository.findings;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Providers;

/**
 * Discovers the findings ledger and binds it to a repository's scoped store, so writers (the scan sweep, the gate,
 * the advisory report) and readers (the API, the console, the CLI) reach the persisted findings through
 * {@code ServiceLoader} rather than a compile-time dependency on the persistence module. A deployment that carries
 * {@code build.jenesis.repository.findings.store} provides one; a deployment without it has none, so
 * {@link #installed()} is empty and everything degrades - writers skip persistence, the {@code /api/findings}
 * surface answers that the store is absent, and the vulnerability views fall back to the live feeds. The provider
 * is stateless: it takes the per-request scoped store on each call, so a single instance serves every tenant and
 * repository.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> One provider instance serves the whole deployment: {@link #over} is called per request,
 *     concurrently from every request thread, so the provider and the {@link Findings} ledger it returns must both
 *     be thread-safe.</li>
 * <li><b>Idempotency / replay.</b> Recording the same finding twice converges on one row rather than duplicating
 *     it - the scan sweep, the gate and the advisory report all re-run over the same coordinates. The ledger
 *     categorises and never discards: a superseded finding is marked, not erased.</li>
 * <li><b>Absence sentinel.</b> {@link #installed()} answers an empty {@link Optional} when no persistence module is
 *     on the module path; every writer then skips persistence and every surface says the store is absent.
 *     {@code null} is never a legal return from {@link #installed()} or {@link #over}.</li>
 * <li><b>Selection failure (&sect;9).</b> This SPI has <em>no</em> selection key - nothing names a ledger by name -
 *     so there is no explicitly-selected miss to fail on. The one resolution failure is ambiguity: two installed
 *     providers would make module-path order decide which ledger the gate reads, so {@link #installed()}
 *     <em>throws</em> naming both rather than picking a discovery-order winner. Resolution runs through the shared
 *     {@link Providers#optionalUnique} primitive, never a hand-rolled loop.</li>
 * <li><b>Tenant scoping (&sect;6).</b> The provider never resolves a tenant: the caller hands in an already-scoped
 *     store and the ledger may read and write nothing outside it.</li>
 * <li><b>Staleness.</b> Every row carries its first- and last-seen instants, so a surface can always say when a
 *     finding was last confirmed rather than presenting an empty ledger ambiguously as "clean".</li>
 * <li><b>Lifecycle / ownership.</b> The caller resolves the provider once and calls {@link #over} per request; the
 *     provider is created by {@link ServiceLoader} and owns whatever the ledger needs to close.
 *     {@link #installed()} caches nothing and closes nothing.</li>
 * <li><b>Ordering / determinism.</b> Which provider {@link #installed()} answers is a function of what is
 *     installed, never of discovery order.</li>
 * <li><b>Bounded work / cancellation.</b> A ledger this provider returns must bound its own repository-wide reads:
 *     {@link Findings#all(Findings.Filter, int, int)} collects no more than the requested window and
 *     {@link Findings#all(Findings.Filter, Findings.Visitor)} materialises nothing, both obligations on the
 *     implementation rather than properties of the interface. The inherited defaults are a small-ledger fallback,
 *     and their bound is <em>visible</em>: they refuse past {@link ArtifactStore#MAX_INHERITED_CHILDREN} matched
 *     rows with an {@link IllegalStateException} naming the class and the override, instead of buffering the
 *     repository's whole findings ledger to serve one console render.</li>
 * </ol>
 */
public interface FindingsProvider {

    /** Bind the findings ledger to one repository's scoped store. */
    Findings over(ArtifactStore store);

    /** The installed provider discovered via {@link ServiceLoader}, resolved through the shared
     *  {@link Providers#optionalUnique} policy: empty when no persistence module is present, and a <em>second</em>
     *  installed provider throws rather than letting module-path order decide which ledger records. */
    static Optional<FindingsProvider> installed() {
        return Providers.singleton("findings", ServiceLoader.load(FindingsProvider.class));
    }
}
