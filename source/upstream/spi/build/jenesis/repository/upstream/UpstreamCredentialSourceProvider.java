package build.jenesis.repository.upstream;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Providers;

/**
 * A named factory for an {@link UpstreamCredentialSource}, discovered at runtime with {@link ServiceLoader} - so
 * where upstream credentials live (the deployment's store; an external secret manager) is a drop-in module and the
 * composition names no backing. Each provider reads its own configuration through the {@code config} lookup (a
 * property accessor returning {@code null} when unset); the deployment's root store is passed for a store-backed
 * source. With no module installed, {@link #resolve} answers {@link UpstreamCredentialSource#NONE}.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> {@link #name()} is a pure declaration; {@link #create} runs once, on the boot thread.
 *     The {@link UpstreamCredentialSource} it returns is consulted per proxied fetch from every request thread, so
 *     <em>that</em> object must be thread-safe.</li>
 * <li><b>Idempotency / replay.</b> {@link #create} may run more than once over the same root store and must then
 *     expose the same credentials; building a source neither writes nor rotates one.</li>
 * <li><b>Absence sentinel.</b> {@link UpstreamCredentialSource#NONE} is the sentinel: with no module installed, or
 *     with the installed provider declining, no credential is ever attached and the management surface says so.
 *     {@link #create} declares "I decline" with an empty {@link Optional}; {@code null} is never a legal return
 *     from it or from {@link #name()}.</li>
 * <li><b>Selection failure (&sect;9).</b> This SPI has <em>no</em> selection key - nothing names a credential
 *     backing by name - so there is no explicitly-selected miss to fail on. The one resolution failure is
 *     ambiguity: two installed providers would make module-path order decide which secret store the proxies
 *     authenticate from, so {@link #resolve} <em>throws</em> naming both rather than picking a discovery-order
 *     winner. Resolution runs through the shared {@link Providers#optionalUnique} primitive, never a hand-rolled
 *     loop.</li>
 * <li><b>Tenant scoping (&sect;6).</b> The source is built over the deployment's <em>root</em> store and its
 *     credentials are deployment-global by design, keyed by upstream host: they authenticate the deployment's own
 *     outbound fetches, never one tenant's data.</li>
 * <li><b>Error visibility (&sect;9).</b> Nothing is swallowed: a provider whose {@link #create} throws
 *     {@link IOException} - an unreachable secret manager, an unreadable credential space - fails resolution as an
 *     {@link UncheckedIOException} naming the provider rather than degrading to the NONE sentinel, because a proxy
 *     that silently drops its credentials fetches anonymously and 401s or, worse, fetches the wrong artifact.</li>
 * <li><b>Read purity (&sect;10).</b> A credential lookup renders stored state; a stored secret is never echoed
 *     back to a management surface, only its presence.</li>
 * <li><b>Lifecycle / ownership.</b> The composition resolves the source once and owns it; {@link #resolve} builds
 *     at most one instance per call, caches nothing and closes nothing.</li>
 * <li><b>Ordering / determinism.</b> The resolved source and {@link #installed()} are functions of what is
 *     installed, never of discovery order.</li>
 * </ol>
 */
public interface UpstreamCredentialSourceProvider {

    /** The source name this provider answers to, e.g. {@code store}. */
    String name();

    /** Build the source, reading settings through {@code config}; empty when off. */
    Optional<UpstreamCredentialSource> create(ArtifactStore root, UnaryOperator<String> config) throws IOException;

    /** Whether any credential-source module is installed - the capability signal a console gates its surface
     *  on. */
    static boolean installed() {
        return !Providers.installedNames("upstream-credentials",
                ServiceLoader.load(UpstreamCredentialSourceProvider.class),
                UpstreamCredentialSourceProvider::name,
                _ -> true).isEmpty();
    }

    /** The single installed source, resolved through the shared {@link Providers#optionalUnique} policy, or
     *  {@link UpstreamCredentialSource#NONE} when no module is installed or the installed one declines. A
     *  <em>second</em> installed provider throws rather than letting module-path order decide which secret store
     *  the proxies authenticate from; the checked {@link IOException} a store-backed source may raise is wrapped
     *  here rather than degrading to the sentinel. */
    static UpstreamCredentialSource resolve(ArtifactStore root, UnaryOperator<String> config) {
        return Providers.optionalUnique("upstream-credentials",
                        ServiceLoader.load(UpstreamCredentialSourceProvider.class),
                        UpstreamCredentialSourceProvider::name,
                        _ -> true,
                        provider -> {
                            try {
                                return provider.create(root, config);
                            } catch (IOException e) {
                                throw new UncheckedIOException("Failed to initialize upstream credentials from "
                                        + provider.name(), e);
                            }
                        })
                .orElse(UpstreamCredentialSource.NONE);
    }
}
