package build.jenesis.repository.dependents.spi;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Providers;

/**
 * Discovers the declared dependents' read model and binds it to a repository's scoped store, so the query surface
 * reaches it without depending on the module that keeps it. Without one {@link #installed()} is empty:
 * the dependents surfaces say the declared index is not installed. Stateless; one instance serves every
 * tenant and repository.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> {@link #over} is called per request, concurrently, so the provider and the returned
 *       {@link DependentsQuery} are thread-safe; the scoped store is the only per-call input.</li>
 *   <li><b>Absence sentinel.</b> {@link #installed()} answers an empty {@link Optional} with no module keeping the
 *       declared dependents - the signal for the declared half to say it is not installed. {@code null} is never returned from {@link #installed()} or
 *       {@link #over}.</li>
 *   <li><b>Selection failure.</b> No key names a provider, so the one failure is ambiguity: two installed providers make
 *       {@link #installed()} throw naming both, through the shared {@link Providers#singleton}.</li>
 *   <li><b>Tenant scoping.</b> The caller hands in an already-scoped store; the query reads nothing outside it.</li>
 *   <li><b>Read purity.</b> Stored rows only - no fetch, no pass on the read path; rows no full pass has built
 *       answer empty, with no stamp.</li>
 *   <li><b>Lifecycle / ownership.</b> The caller resolves the provider once and calls {@link #over} per request. The
 *       provider may cache per-scope readers it owns and closes; {@link #installed()} caches and closes nothing.</li>
 *   <li><b>Ordering / determinism.</b> Which provider answers depends on what is installed, never on discovery
 *       order.</li>
 *   <li><b>Bounded work / cancellation.</b> A returned query answers {@link DependentsQuery#declarations} a bounded
 *       page at a time, its size capped by the caller's limit, and {@link DependentsQuery#declarationsBuiltAt} with
 *       one read of the pass's stamp.</li>
 * </ol>
 */
public interface DependentsQueryProvider {

    /** Bind the declared-dependencies read model to one repository's scoped store. */
    DependentsQuery over(ArtifactStore store);

    /** The installed provider, through the shared {@link Providers#singleton}: empty without a module keeping the declared dependents, and a
     *  second installed provider throws rather than letting module-path order decide which read model answers. */
    static Optional<DependentsQueryProvider> installed() {
        return Providers.singleton("dependents", ServiceLoader.load(DependentsQueryProvider.class));
    }
}
