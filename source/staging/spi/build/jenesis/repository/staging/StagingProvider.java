package build.jenesis.repository.staging;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Providers;

/**
 * A named factory for a {@link Staging} implementation, discovered with {@link ServiceLoader}, so the composition names
 * no implementation. Each provider reads its configuration through {@code config} and yields empty when not enabled.
 * Without one {@link #resolve} is empty: the endpoints answer {@code 501} and the console hides the staging surface.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> {@link #name()} is a pure declaration; {@link #create} runs once, on the boot thread. The
 *       returned {@link Factory} is shared and {@link Factory#over} is called per request, so both it and the
 *       {@link Staging} operations it builds are thread-safe.</li>
 *   <li><b>Idempotency / replay.</b> Promotion re-points content-addressed blobs, so a replay after a crash converges
 *       on the same release layout; a drop never disturbs a release.</li>
 *   <li><b>Absence sentinel.</b> An empty {@link Optional}, from {@link #create} and {@link #resolve} alike.
 *       {@code null} is never returned from {@link #name()}, {@link #create} or {@link Factory#over}.</li>
 *   <li><b>Selection failure.</b> No key names an implementation, so the one failure is ambiguity: two installed
 *       providers make {@link #resolve} throw naming both, through the shared {@link Providers#optionalUnique}.</li>
 *   <li><b>Tenant scoping.</b> {@link Factory#over} receives an already-scoped repository store; nothing outside it is
 *       read or written.</li>
 *   <li><b>Durability / delivery.</b> A promotion's commit point is the release pointer write; the staged blobs exist
 *       before it, so a crash between leaves unreferenced content for collection, never a half-visible release.</li>
 *   <li><b>Lifecycle / ownership.</b> The composition resolves the factory once and calls {@link Factory#over} per
 *       request; {@link #resolve} builds at most one factory per call and caches and closes nothing.</li>
 *   <li><b>Ordering / determinism.</b> The resolved factory depends on what is installed, never on discovery
 *       order.</li>
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

    /** The single installed implementation through the shared {@link Providers#optionalUnique}: empty when none is
     *  installed or it declines; a second installed provider throws. */
    static Optional<Factory> resolve(UnaryOperator<String> config) {
        return Providers.optionalUnique("staging",
                ServiceLoader.load(StagingProvider.class),
                StagingProvider::name,
                _ -> true,
                provider -> provider.create(config));
    }
}
