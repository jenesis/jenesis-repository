package build.jenesis.repository.store;

import module java.base;

/**
 * A named factory for an {@link ArtifactStore} backend, discovered at runtime with {@link ServiceLoader}.
 * The store is an <em>exclusive</em> SPI in the {@link Features} convention: the server selects one by name
 * (the {@code jenesis.repository.store} setting, default {@code filesystem} - the most universally applicable
 * backend); each provider reads its own configuration through the {@code config} lookup, staying free of any
 * framework dependency. <strong>Every</strong> backend - the bundled filesystem one no less than S3 / GCS / Azure
 * Blob - is added to the module graph by the distribution and bound here through {@code provides}: no consumer, the
 * server included, {@code requires} an implementation, so which backends a deployment can select is a packaging
 * decision (see the {@code bundle} module) and not an edge that rebuilds every consumer whenever a backend changes.
 * A selected backend whose {@link #requiredConfig() required configuration} is unset fails loudly rather than self-disabling:
 * silently falling back to another store would serve and persist against the wrong backend.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> {@link #name()} and {@link #requiredConfig()} are pure declarations callable from any
 *     thread; {@link #create} runs once, on the boot thread, before the web layer is up. The {@link ArtifactStore} it
 *     returns is a shared singleton the server calls concurrently from every request thread, so <em>that</em> object
 *     must be thread-safe - the provider itself need not be.</li>
 * <li><b>Idempotency / replay.</b> {@link #create} may be called more than once (a second application context, a test
 *     harness) and must then hand back an equivalent view of the same durable content rather than reformatting,
 *     truncating or re-provisioning the backend. Building a store performs no destructive setup.</li>
 * <li><b>Absence sentinel.</b> There is none: exactly one backend always resolves. {@link #resolve} answers a live
 *     store or throws - it never returns {@code null}, and a provider returning {@code null} from {@link #create}
 *     fails loudly naming the provider class. {@link #name()} and {@link #requiredConfig()} may not return
 *     {@code null} either; an empty {@link #requiredConfig()} means "needs nothing".</li>
 * <li><b>Selection failure (&sect;9).</b> An explicitly selected backend that no provider answers to - its module is
 *     off the module path, or the name is misspelled - throws {@link IllegalStateException} at resolution naming the
 *     selection, the {@code filesystem} default it refuses to fall back to, and the installed provider names. A
 *     selected backend whose {@link #requiredConfig()} is unset likewise throws, naming <em>every</em> missing key at
 *     once, and is never constructed. This SPI deliberately does not self-disable the way an optional capability may:
 *     {@code jenesis.repository.store=s3} with the s3 module absent must not boot against the local disk, publishing
 *     into ephemeral storage while every artifact in the intended bucket 404s. Only an <em>unselected</em> deployment
 *     gets the {@code filesystem} default, and its required configuration is checked just the same.</li>
 * <li><b>Error visibility (&sect;9).</b> Nothing is swallowed. Two providers answering to one name, or one provider
 *     registered twice, are packaging errors and throw rather than letting module-path order pick the backend a
 *     deployment persists into. Configuration problems surface as one message naming the keys, never as a degraded
 *     store.</li>
 * <li><b>Lifecycle / ownership.</b> The composition owns the store: {@link #resolve} constructs exactly one instance
 *     and hands it over, caching nothing and closing nothing. A provider may hand its store a client, connection pool
 *     or thread it owns, and the store closes them through its own lifecycle; the provider instance itself is
 *     discarded immediately and must hold no state a later call depends on.</li>
 * <li><b>Ordering / determinism.</b> The resolved backend is a function of the configured name and the installed
 *     providers only - never of {@link ServiceLoader} discovery order. Providers are matched by name
 *     case-insensitively and every diagnostic lists them in one stable, name-sorted order.</li>
 * <li><b>Transport security (&sect;13).</b> A backend an operator points at an <em>endpoint</em> requires that
 *     endpoint to be {@code https}, and refuses a plaintext one at resolution with an {@link IllegalStateException}
 *     whose message <b>names the opt-out key</b> - because credentials and every artifact byte would otherwise travel
 *     in clear with no operator signal, and the operator running a local emulator needs to be told what to set. It is
 *     an opt-out, not a ban: the very same configuration resolves once an explicit
 *     {@code JENESIS_<BACKEND>_ALLOW_INSECURE_ENDPOINT=true} is set, which is how the containerised emulators are
 *     reached. The rule binds however the endpoint reaches the provider - as its own setting for {@code s3} and
 *     {@code gcs}, or buried inside a connection string that also carries the account key for {@code azure-blob},
 *     where the scheme is easiest to mistype and most costly to get wrong. A backend with no endpoint at all (the
 *     bundled {@code filesystem}) has no transport to screen. Stating the rule at the SPI rather than three times over
 *     is what makes the next endpoint-configured backend arrive with it; {@code StoreContract}'s
 *     {@code PLAINTEXT_ENDPOINT_REFUSED} property drives it through {@link #resolve} with each fixture's own
 *     config.</li>
 * </ol>
 */
public interface ArtifactStoreProvider {

    /** The backend name this provider answers to, e.g. {@code filesystem}, {@code s3}, {@code gcs}, {@code azure-blob}. */
    String name();

    /** Build the backend, reading configuration through {@code config} (a property/env lookup returning null if unset). */
    ArtifactStore create(UnaryOperator<String> config);

    /** The config keys this backend cannot run without (a bucket, a connection string) - empty (the default) for a
     *  backend that needs nothing. A credential with an ambient fallback (an instance role, a default chain) is not
     *  required config. {@link #resolve} checks these up front so a misconfigured selection fails with one message
     *  naming every missing key. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /** Resolve the named backend through the shared {@link Providers#exclusiveWithDefault} policy: the bundled
     *  {@code filesystem} backend answers an <em>unselected</em> deployment, an explicitly named backend no provider
     *  answers to fails loudly rather than silently serving and persisting against the local disk, and the chosen
     *  backend's {@link #requiredConfig() required configuration} is validated before it is ever built. Discovery
     *  stays here, with the {@code uses} clause: the primitive resolves over the loader it is handed.
 */
    static ArtifactStore resolve(String name, UnaryOperator<String> config) {
        return Providers.exclusiveWithDefault("store",
                ServiceLoader.load(ArtifactStoreProvider.class),
                ArtifactStoreProvider::name,
                Optional.ofNullable(name),
                "filesystem",
                provider -> Features.missing(provider.requiredConfig(), config),
                provider -> provider.create(config));
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
                    + " is required for the " + backend + " artifact store backend.");
        }
        return value;
    }
}
