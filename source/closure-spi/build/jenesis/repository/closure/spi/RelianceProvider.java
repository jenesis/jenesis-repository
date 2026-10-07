package build.jenesis.repository.closure.spi;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Where {@link Reliance} comes from: the module that keeps what relies on what.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> One provider serves every caller on every thread, and {@link #over} is called per
 *       request, so a provider holds no state of its own; the reliance it answers is bound to its arguments.</li>
 *   <li><b>Absence sentinel.</b> With no provider installed {@link Reliance#over} answers {@link Reliance#NONE};
 *       {@link #over} itself never answers {@code null}.</li>
 *   <li><b>Selection failure.</b> {@code OPTIONAL_UNIQUE} with no selection key: two installed providers fail
 *       resolution naming both, on every call, rather than letting module-path order decide whose rows a page
 *       reads.</li>
 *   <li><b>Tenant scoping.</b> {@code holder} is one repository's store, {@code repositories} resolves names within
 *       its tenant alone and {@code tenant} is that tenant's store; a reliance reads nothing outside them.</li>
 *   <li><b>Read purity.</b> {@link #over} reads nothing: it binds a reliance whose answers read stored state.</li>
 *   <li><b>Lifecycle / ownership.</b> Discovered on first use and held for the process, since the module graph fixes
 *       the installed set; a provider owns no thread, client or connection and is never closed.</li>
 * </ol>
 */
public interface RelianceProvider {

    /** The reliance over {@code holder}, the store of the repository named {@code holderName}, reading its tenant's
     *  other repositories through {@code repositories} and the tenant's store {@code tenant}. */
    Reliance over(ArtifactStore holder, String holderName, Optional<ArtifactStore> tenant,
                  Function<String, Optional<ArtifactStore>> repositories);
}
