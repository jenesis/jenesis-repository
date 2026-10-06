package build.jenesis.repository.closure.spi;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Where {@link Reliance} comes from: the module that keeps what relies on what. Selection is {@code OPTIONAL_UNIQUE} -
 * absent, every reliance is {@link Reliance#NONE}; two installed fail discovery, naming both. Discovered once for the
 * JVM's life, since the module graph fixes the installed set.
 */
public interface RelianceProvider {

    /** The reliance over {@code holder}, the store of the repository named {@code holderName}, reading its tenant's
     *  other repositories through {@code repositories} and the tenant's store {@code tenant}. */
    Reliance over(ArtifactStore holder, String holderName, Optional<ArtifactStore> tenant,
                  Function<String, Optional<ArtifactStore>> repositories);
}
