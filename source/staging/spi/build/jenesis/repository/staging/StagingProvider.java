package build.jenesis.repository.staging;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Providers;

/**
 * A named factory for a {@link Staging} implementation, discovered at runtime with {@link ServiceLoader} - so the
 * staging lifecycle is a drop-in module that {@code provides} this interface, and the composition names no
 * implementation. Each provider reads its own configuration through the {@code config} lookup (a property/setting
 * accessor returning {@code null} when unset), staying free of any framework dependency, and yields empty when its
 * implementation is not enabled. With no provider installed, {@link #resolve} is empty and staging degrades: the
 * endpoints answer {@code 501} and the console hides the staging surface.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> {@link #name()} is a pure declaration; {@link #create} runs once, on the boot thread.
 *     The {@link Factory} it returns is shared and its {@link Factory#over} is called per request, so both the
 *     factory and the {@link Staging} operations it builds must be thread-safe.</li>
 * <li><b>Idempotency / replay.</b> Promotion is re-pointing content-addressed blobs, so a promotion replayed after
 *     a crash converges on the same release layout rather than duplicating or corrupting it; a drop never disturbs
 *     a release.</li>
 * <li><b>Absence sentinel.</b> An empty {@link Optional} is the sentinel, from {@link #create} and from
 *     {@link #resolve} alike: staging degrades, the endpoints answer {@code 501} and the console hides the surface.
 *     {@code null} is never a legal return from {@link #name()}, {@link #create} or {@link Factory#over}.</li>
 * <li><b>Selection failure (&sect;9).</b> This SPI has <em>no</em> selection key - nothing names a staging
 *     implementation by name - so there is no explicitly-selected miss to fail on. The one resolution failure is
 *     ambiguity: two installed providers would make module-path order decide which lifecycle a promotion runs
 *     through, so {@link #resolve} <em>throws</em> naming both rather than picking a discovery-order winner.
 *     Resolution runs through the shared {@link Providers#optionalUnique} primitive, never a hand-rolled loop.</li>
 * <li><b>Tenant scoping (&sect;6).</b> {@link Factory#over} receives an already-scoped repository store; the
 *     staging operations may read and write nothing outside it.</li>
 * <li><b>Durability / delivery.</b> The commit point of a promotion is the release-layout pointer write; the
 *     staged blobs exist beforehand, so a crash between them leaves unreferenced content the reclamation sweep
 *     collects, never a half-visible release.</li>
 * <li><b>Lifecycle / ownership.</b> The composition resolves the factory once and calls {@link Factory#over} per
 *     request; {@link #resolve} builds at most one factory per call, caches nothing and closes nothing.</li>
 * <li><b>Ordering / determinism.</b> The resolved factory is a function of what is installed, never of discovery
 *     order.</li>
 * </ol>
 */
public interface StagingProvider {

    /** The implementation name this provider answers to, e.g. {@code store}. */
    String name();

    /** Build the factory if the configuration enables it, reading settings through {@code config}; empty when off. */
    Optional<Factory> create(UnaryOperator<String> config);

    /** Builds the {@link Staging} operations over one repository's scoped store. */
    @FunctionalInterface
    interface Factory {
        Staging over(ArtifactStore store);
    }

    /** The single installed implementation, resolved through the shared {@link Providers#optionalUnique} policy:
     *  empty when none is installed or the installed one declines, and a <em>second</em> installed provider throws
     *  rather than letting module-path order decide which lifecycle a promotion runs through. */
    static Optional<Factory> resolve(UnaryOperator<String> config) {
        return Providers.optionalUnique("staging",
                ServiceLoader.load(StagingProvider.class),
                StagingProvider::name,
                _ -> true,
                provider -> provider.create(config));
    }
}
