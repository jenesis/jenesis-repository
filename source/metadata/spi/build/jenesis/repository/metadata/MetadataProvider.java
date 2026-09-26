package build.jenesis.repository.metadata;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Providers;

/**
 * Discovers the consolidated metadata store and binds it to a repository's scoped store, so writers and readers
 * reach the persisted document through {@link ServiceLoader} rather than a compile-time dependency on the
 * persistence module. Every composition carries one - both bundles, the harness's repository and every test
 * composition that reaches a consumer - so a consumer has one plane to write and read, never a second layout for a
 * deployment without it; a composition that lacks it fails the first time a consumer asks, naming the module to add.
 * The provider is stateless: it takes the per-request scoped store on each call, so a single instance serves every
 * tenant and repository.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> One provider instance serves the whole deployment: {@link #over} is called per request
 *     and per sweep, concurrently, so the provider and the {@link MetadataStore} it returns must both be
 *     thread-safe.</li>
 * <li><b>Idempotency / replay.</b> A section write is a compare-and-set over the whole document, so a repeated
 *     write converges on one document rather than duplicating sections, and an older node never drops a newer
 *     writer's section on a conflicting update.</li>
 * <li><b>Absence.</b> A composition without a persistence module is a composition error, not a mode:
 *     {@link #installed()} throws {@link IllegalStateException} naming the module to add. {@code null} is never a
 *     legal return.</li>
 * <li><b>Selection failure (&sect;9).</b> This SPI has <em>no</em> selection key - nothing names a document store
 *     by name - so there is no explicitly-selected miss to fail on. The one resolution failure is ambiguity: two
 *     installed providers would make module-path order decide which document store every subsystem's sections land
 *     in, so {@link #installed()} <em>throws</em> naming both rather than picking a discovery-order winner.
 *     Resolution runs through the shared {@link Providers#singleton} primitive, never a hand-rolled loop.</li>
 * <li><b>Tenant scoping (&sect;6).</b> The provider never resolves a tenant: the caller hands in an already-scoped
 *     store and the document store reads and writes nothing outside it.</li>
 * <li><b>Staleness.</b> Each {@link Section} carries its own {@code updated} instant and {@code state}, so a
 *     surface distinguishes "derived and empty" from "never derived" without guessing.</li>
 * <li><b>Lifecycle / ownership.</b> The caller resolves the provider once and calls {@link #over} per request;
 *     {@link #installed()} caches nothing and closes nothing.</li>
 * <li><b>Ordering / determinism.</b> Which provider {@link #installed()} answers is a function of what is
 *     installed, never of discovery order.</li>
 * </ol>
 */
public interface MetadataProvider {

    /** Bind the consolidated metadata store to one repository's scoped store. */
    MetadataStore over(ArtifactStore store);

    /** The installed provider discovered via {@link ServiceLoader}, resolved through the shared
     *  {@link Providers#singleton} policy: exactly one, so a composition with none throws naming the module to add,
     *  and a <em>second</em> installed provider throws rather than letting module-path order decide where the
     *  sections land. */
    static MetadataProvider installed() {
        return Providers.singleton("metadata", ServiceLoader.load(MetadataProvider.class))
                .orElseThrow(() -> new IllegalStateException("No metadata store is installed: every composition "
                        + "carries one, so add 'requires build.jenesis.repository.metadata.store;' to this "
                        + "composition's module"));
    }
}
