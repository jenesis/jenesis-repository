package build.jenesis.repository.cache.storage;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Providers;
import build.jenesis.repository.icon.IconContributor;

/**
 * A named factory for a {@link CacheStorage} backend, discovered with {@link ServiceLoader}. There is no selection key:
 * the cache has no backend of its own and delegates into a segment of the repository's store, which the deployment
 * already selected. A provider reads its configuration through the {@code config} lookup {@link #create} is handed,
 * backed by the caller's Spring {@code Environment}, so it stays free of any framework. It resolves through the same
 * {@link Providers} exclusive-with-default primitives as {@code ArtifactStoreProvider}, so the two selections accept,
 * refuse and explain alike.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> {@link #name()} and {@link #requiredConfig()} are pure declarations; {@link #create} runs
 *       once, on the boot thread. The {@link CacheStorage} it returns is a shared singleton and must be thread-safe;
 *       the provider need not be.</li>
 *   <li><b>Idempotency / replay.</b> {@link #create} called again hands back an equivalent view of the same content,
 *       never re-provisioning or emptying the backend; creating an existing bucket or container converges.</li>
 *   <li><b>Absence sentinel.</b> None: exactly one backend always resolves, and {@link #resolve} answers a storage or
 *       throws. A {@code null} from {@link #create} fails naming the provider class; {@link #name()} and
 *       {@link #requiredConfig()} never return {@code null}, an empty set meaning "needs nothing".</li>
 *   <li><b>Selection failure.</b> A selected backend no provider answers to throws {@link IllegalStateException} at
 *       resolution, naming the selection, the {@code filesystem} default it refuses to fall back to, and the installed
 *       names. A selected backend with unset {@link #requiredConfig()} throws naming every missing key, and is never
 *       built. Falling back would persist the cache to the wrong place while the intended bucket stays empty. Only an
 *       unselected deployment gets the default, its required configuration checked the same.</li>
 *   <li><b>Error visibility.</b> Nothing is swallowed. Two providers answering one name, or one registered twice, throw
 *       rather than letting module-path order decide.</li>
 *   <li><b>Tenant scoping.</b> {@link #resolve} builds the root storage; a caller scopes it per tenant before any
 *       content is touched, and a backend honours the scope as a traversal-guarded prefix.</li>
 *   <li><b>Lifecycle / ownership.</b> {@link #resolve} constructs exactly one instance and hands it over, caching and
 *       closing nothing. A storage closes the client, pool or thread its provider gave it; the provider instance is
 *       discarded and holds no state.</li>
 *   <li><b>The store it delegates into.</b> A backend keeping entries in the artifact store takes the node's store
 *       ({@link #create(UnaryOperator, ArtifactStore)}), the metered one, so the cache's store cost is counted with the
 *       rest; handed none, it resolves its own.</li>
 *   <li><b>Ordering / determinism.</b> The resolved backend depends on the configured name and the installed providers,
 *       never on discovery order; names match case-insensitively and every diagnostic lists them name-sorted.</li>
 * </ol>
 */
public interface CacheStorageProvider extends IconContributor {

    /** The backend name this provider answers to, e.g. {@code filesystem} or {@code azure-blob}. */
    String name();

    /** Build the backend, reading its configuration through {@code config}, a property-key lookup answering the value
     *  or {@code null}, backed by the Spring {@code Environment} so a property and its relaxed-binding variable both
     *  work. */
    CacheStorage create(UnaryOperator<String> config);

    /** Build the backend over the node's artifact store, where it delegates into one, so its store cost is metered. A
     *  backend with a store of its own ignores the argument. */
    default CacheStorage create(UnaryOperator<String> config, ArtifactStore store) {
        return create(config);
    }

    /** The config keys this backend cannot run without, empty by default; a credential with an ambient fallback is not
     *  one. {@link #resolve} checks them first, so a misconfigured selection fails naming every missing key. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /** Resolve the cache storage. There is no name to pass: a second selection beside the artifact store's would make
     *  the storage configuration ambiguous, both reading the same keys. Exactly one implementation resolves, its
     *  required configuration validated first; a distribution replaces the bundled provider by answering to its
     *  name. */
    static CacheStorage resolve(UnaryOperator<String> config) {
        return resolve(config, null);
    }

    /** {@link #resolve(UnaryOperator)} over the node's artifact store ({@link #create(UnaryOperator, ArtifactStore)});
     *  {@code null} lets a delegating backend resolve its own. */
    static CacheStorage resolve(UnaryOperator<String> config, ArtifactStore store) {
        return Providers.exclusiveWithDefault("cache-storage",
                ServiceLoader.load(CacheStorageProvider.class),
                CacheStorageProvider::name,
                Optional.empty(),
                "delegating",
                provider -> Features.missing(provider.requiredConfig(), config),
                provider -> store == null ? provider.create(config) : provider.create(config, store));
    }

    /** The value of a required setting, or a failure naming the deployment key to set, shared so every backend says it
     *  the same way. {@link #resolve} validates {@link #requiredConfig()} first, so this fires only for a direct
     *  {@code create}. */
    static String required(UnaryOperator<String> config, String setting, String backend) {
        String value = config.apply(setting);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(setting
                    + " is required for the " + backend + " storage backend.");
        }
        return value;
    }
}
