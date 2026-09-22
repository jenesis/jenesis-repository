package build.jenesis.repository.cache.storage;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Providers;
import build.jenesis.repository.icon.IconContributor;

/**
 * A named factory for a {@link CacheStorage} backend, discovered at runtime with {@link ServiceLoader}.
 * Callers (the dispatcher, the admin ui, and the combined single-node app) take the one implementation that
 * resolves; there is no selection key, because the cache has no backend of its own and delegates into a segment
 * of the repository's store, which the deployment already selected. Each provider reads its own
 * configuration through the {@code config} lookup passed to {@link #create}, which the caller backs
 * with its Spring {@code Environment}; the provider stays free of any framework or {@code System.getenv}
 * dependency. This is what lets the lean, modular apps discover an optional backend (e.g. S3 or Azure
 * Blob) without a compile-time dependency on it - the backend module is added to the module graph at
 * deploy time and bound here through {@code provides}.
 *
 * <p>This is the cache-side sibling of {@code ArtifactStoreProvider}: the same
 * <em>exclusive-with-default</em> policy, resolved through the same shared {@link Providers} primitives, so the two
 * store selections cannot drift apart in what they accept, what they refuse and what they say when they refuse.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> {@link #name()} and {@link #requiredConfig()} are pure declarations callable from any
 *     thread; {@link #create} runs once, on the boot thread, before the web layer is up. The {@link CacheStorage} it
 *     returns is a shared singleton every request thread reads and writes through, so <em>that</em> object must be
 *     thread-safe - the provider itself need not be.</li>
 * <li><b>Idempotency / replay.</b> {@link #create} may be called more than once (a second application context, a
 *     test harness) and must then hand back an equivalent view of the same durable content rather than
 *     re-provisioning, reformatting or emptying the backend. Creating a bucket or container that already exists
 *     converges rather than failing.</li>
 * <li><b>Absence sentinel.</b> There is none: exactly one backend always resolves. {@link #resolve} answers a live
 *     storage or throws - it never returns {@code null}, and a provider returning {@code null} from {@link #create}
 *     fails loudly naming the provider class. {@link #name()} and {@link #requiredConfig()} may not return
 *     {@code null} either; an empty {@link #requiredConfig()} means "needs nothing".</li>
 * <li><b>Selection failure (&sect;9).</b> An explicitly selected backend that no provider answers to - its module is
 *     off the module path, or the name is misspelled - throws {@link IllegalStateException} at resolution naming the
 *     selection, the {@code filesystem} default it refuses to fall back to, and the installed provider names. A
 *     selected backend whose {@link #requiredConfig()} is unset likewise throws, naming <em>every</em> missing key
 *     at once, and is never constructed. This SPI deliberately does not self-disable the way an optional capability
 *     may: falling back would persist the cache against the wrong backend - objects land in ephemeral local storage
 *     while the intended bucket stays empty and every read misses. Only an <em>unselected</em> deployment gets the
 *     {@code filesystem} default, and its required configuration is checked just the same.</li>
 * <li><b>Error visibility (&sect;9).</b> Nothing is swallowed. Two providers answering to one name, or one provider
 *     registered twice, are packaging errors and throw rather than letting module-path order pick the backend a
 *     deployment persists into. Configuration problems surface as one message naming the keys, never as a degraded
 *     storage.</li>
 * <li><b>Tenant scoping (&sect;6).</b> {@link #resolve} builds the deployment's <em>root</em> storage; a caller
 *     scopes it per tenant before any content is read or written, and a backend must honour that scoping as a
 *     traversal-guarded key prefix rather than a hint.</li>
 * <li><b>Lifecycle / ownership.</b> The composition owns the storage: {@link #resolve} constructs exactly one
 *     instance and hands it over, caching nothing and closing nothing. A provider may hand its storage a client,
 *     connection pool or thread it owns, and the storage closes them through its own lifecycle; the provider
 *     instance itself is discarded immediately and must hold no state a later call depends on.</li>
 * <li><b>The store it delegates into.</b> A backend that keeps its entries in the repository's artifact store
 *     takes the store the node hands it ({@link #create(UnaryOperator, ArtifactStore)}) rather than resolving a
 *     second instance of the same backend: the node's store is the metered one, so what the cache costs the store
 *     is counted with everything else the node does. Handed none, it resolves its own.</li>
 * <li><b>Ordering / determinism.</b> The resolved backend is a function of the configured name and the installed
 *     providers only - never of {@link ServiceLoader} discovery order. Providers are matched by name
 *     case-insensitively and every diagnostic lists them in one stable, name-sorted order.</li>
 * </ol>
 */
public interface CacheStorageProvider extends IconContributor {

    /** The backend name this provider answers to, e.g. {@code filesystem} or {@code azure-blob}. */
    String name();

    /**
     * Build the backend, reading its configuration through {@code config}: a property-key lookup
     * (e.g. {@code config.apply("s3.bucket")}) returning the configured value or {@code null}.
     * The caller backs it with the application's Spring {@code Environment}, so the same value can be set
     * through a property or its relaxed-binding environment variable (e.g. {@code JENREG_S3_BUCKET}).
     */
    CacheStorage create(UnaryOperator<String> config);

    /**
     * Build the backend over the artifact store the node already holds, where the backend delegates into one: the
     * node's store is the metered one, and a backend that resolved its own would count nothing of what the cache
     * costs it. A backend with a store of its own ignores the argument, which is the default.
     */
    default CacheStorage create(UnaryOperator<String> config, ArtifactStore store) {
        return create(config);
    }

    /** The config keys this backend cannot run without (a bucket, a connection string) - empty (the default) for a
     *  backend that needs nothing. A credential with an ambient fallback (an instance role, a default chain) is not
     *  required config. {@link #resolve} checks these up front so a misconfigured selection fails with one message
     *  naming every missing key, before the backend is built. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /**
     * Resolve the cache storage. There is no name to pass: the cache has no backend of its own.
     *
     * <p>It used to take one, and four providers answered to it - {@code filesystem}, {@code s3}, {@code gcs},
     * {@code azure-blob} - each resolving the artifact store of the same name through a
     * {@code jenreg.cache-storage} key. That second selection was never intended, nothing could reach it (no
     * deployment path offered a cache backend), and it made the product's storage configuration ambiguous: both
     * roles read the same {@code jenreg.s3.*} keys, so a repository on disk with a cache in a bucket was
     * indistinguishable from a misconfiguration. One selection means one store.
     *
     * <p>The policy is unchanged in kind - exactly one implementation always resolves, and its required
     * configuration is validated before it is built - it simply has nothing to choose between. A distribution that
     * ships its own provider replaces the bundled one by answering to the same name.
     */
    static CacheStorage resolve(UnaryOperator<String> config) {
        return resolve(config, null);
    }

    /** {@link #resolve(UnaryOperator)} over the artifact store the node holds - see {@link #create(UnaryOperator,
     *  ArtifactStore)}; {@code null} lets a delegating backend resolve its own, as a composition without one must. */
    static CacheStorage resolve(UnaryOperator<String> config, ArtifactStore store) {
        return Providers.exclusiveWithDefault("cache-storage",
                ServiceLoader.load(CacheStorageProvider.class),
                CacheStorageProvider::name,
                Optional.empty(),
                "delegating",
                provider -> Features.missing(provider.requiredConfig(), config),
                provider -> store == null ? provider.create(config) : provider.create(config, store));
    }

    /**
     * The value of a required setting, or a failure naming the key an operator has to set.
     *
     * <p>{@code setting} is the deployment key, which is what {@link #requiredConfig()} declares and what the
     * message must name: the person reading it has to go and set exactly that. Backends share this rather than each
     * writing the null-or-blank check and its own spelling of the same sentence.
     *
     * <p>{@link #resolve} validates {@link #requiredConfig()} before a backend is ever built, so this fires only for
     * a {@code create} called directly - a fixture, an embedder - which is the one path that skips that check.
     */
    static String required(UnaryOperator<String> config, String setting, String backend) {
        String value = config.apply(setting);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(setting
                    + " is required for the " + backend + " storage backend.");
        }
        return value;
    }
}
