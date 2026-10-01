package build.jenesis.repository.upstream;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Providers;

/**
 * A named factory for an {@link UpstreamCredentialSource}, discovered with {@link ServiceLoader}, so where upstream
 * credentials live is a drop-in module. Each provider reads its configuration through {@code config}; the deployment's
 * root store is passed for a store-backed source. With none installed {@link #resolve} answers
 * {@link UpstreamCredentialSource#NONE}.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> {@link #name()} is a pure declaration; {@link #create} runs once, on the boot thread. The
 *       returned source is consulted per proxied fetch from every request thread, so it is thread-safe.</li>
 *   <li><b>Idempotency / replay.</b> {@link #create} may run more than once over one root store and then exposes the
 *       same credentials; building a source writes and rotates nothing.</li>
 *   <li><b>Absence sentinel.</b> {@link UpstreamCredentialSource#NONE}, with no module or a declining provider.
 *       {@link #create} declines with an empty {@link Optional}; {@code null} is never returned from it or
 *       {@link #name()}.</li>
 *   <li><b>Selection failure.</b> No key names a credential backing, so the one failure is ambiguity: two installed
 *       providers make {@link #resolve} throw naming both, through the shared {@link Providers#optionalUnique}, rather
 *       than let module-path order decide which secret store the proxies use.</li>
 *   <li><b>Tenant scoping.</b> Built over the root store, with deployment-global credentials keyed by upstream host:
 *       they authenticate the deployment's own outbound fetches, never one tenant's data.</li>
 *   <li><b>Error visibility.</b> A {@link #create} throwing {@link IOException} - an unreachable secret manager, an
 *       unreadable credential space - fails resolution as an {@link UncheckedIOException} naming the provider rather
 *       than degrading to NONE, since a proxy silently dropping its credentials fetches anonymously.</li>
 *   <li><b>Read purity.</b> A lookup renders stored state; a secret is never echoed to a management surface, only its
 *       presence.</li>
 *   <li><b>Lifecycle / ownership.</b> The composition resolves the source once and owns it; {@link #resolve} builds at
 *       most one per call and caches and closes nothing.</li>
 *   <li><b>Ordering / determinism.</b> The resolved source and {@link #installed()} depend on what is installed, never
 *       on discovery order.</li>
 * </ol>
 */
public interface UpstreamCredentialSourceProvider {

    /** The source name this provider answers to, e.g. {@code store}. */
    String name();

    /** Build the source, reading settings through {@code config}; empty when off. */
    Optional<UpstreamCredentialSource> create(ArtifactStore root, UnaryOperator<String> config) throws IOException;

    /** Whether any credential-source module is installed - the capability a console gates its surface on. */
    static boolean installed() {
        return !Providers.installedNames("upstream-credentials",
                ServiceLoader.load(UpstreamCredentialSourceProvider.class),
                UpstreamCredentialSourceProvider::name,
                _ -> true).isEmpty();
    }

    /** The single installed source through the shared {@link Providers#optionalUnique}, or
     *  {@link UpstreamCredentialSource#NONE} when none is installed or it declines; a second installed provider throws,
     *  and a store-backed source's {@link IOException} is wrapped rather than degrading to the sentinel. */
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
