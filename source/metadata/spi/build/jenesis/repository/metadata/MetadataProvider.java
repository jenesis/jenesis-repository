package build.jenesis.repository.metadata;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Providers;

/**
 * Discovers the consolidated metadata store and binds it to a repository's scoped store, so writers and readers reach
 * the document without depending on the persistence module. Every composition carries one; one that lacks it fails the
 * first time a consumer asks, naming the module to add. Stateless: the scoped store is handed in per call, so one
 * instance serves every tenant and repository.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> {@link #over} is called per request and per sweep, concurrently, so the provider and the
 *       {@link MetadataStore} it returns are thread-safe.</li>
 *   <li><b>Idempotency / replay.</b> A section write is a compare-and-set over the whole document, so a repeated write
 *       converges on one document, and an older node never drops a newer writer's section.</li>
 *   <li><b>Absence.</b> A composition without a persistence module is an error, not a mode: {@link #installed()} throws
 *       {@link IllegalStateException} naming the module to add. {@code null} is never returned.</li>
 *   <li><b>Selection failure.</b> No key selects a document store, so the one failure is ambiguity: two installed
 *       providers make {@link #installed()} throw naming both rather than let module-path order decide, through the
 *       shared {@link Providers#singleton}.</li>
 *   <li><b>Tenant scoping.</b> The caller hands in an already-scoped store and the document store touches nothing
 *       outside it.</li>
 *   <li><b>Staleness.</b> Each {@link Section} carries its own {@code updated} instant and {@code state}, so a surface
 *       tells "derived and empty" from "never derived".</li>
 *   <li><b>Lifecycle / ownership.</b> The caller resolves the provider once and calls {@link #over} per request;
 *       {@link #installed()} caches and closes nothing.</li>
 *   <li><b>Ordering / determinism.</b> Which provider {@link #installed()} answers depends on what is installed, never
 *       on discovery order.</li>
 * </ol>
 */
public interface MetadataProvider {

    /** Bind the consolidated metadata store to one repository's scoped store. */
    MetadataStore over(ArtifactStore store);

    /** The installed provider, through {@link Providers#singleton}: exactly one, so none throws naming the module to
     *  add and a second throws rather than letting module-path order decide. */
    static MetadataProvider installed() {
        return Providers.singleton("metadata", ServiceLoader.load(MetadataProvider.class))
                .orElseThrow(() -> new IllegalStateException("No metadata store is installed: every composition "
                        + "carries one, so add 'requires build.jenesis.repository.metadata.store;' to this "
                        + "composition's module"));
    }
}
