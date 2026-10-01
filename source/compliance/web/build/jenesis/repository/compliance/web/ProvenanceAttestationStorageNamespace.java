package build.jenesis.repository.compliance.web;

import module java.base;
import build.jenesis.repository.maintenance.StorageNamespace;

/**
 * Declares the per-repository {@code provenance-attestation} space of {@link ProvenanceAttestationCache}, reclaimed by
 * {@link ProvenanceAttestationReaper}, so the orphan diagnostic and the purge see it.
 */
public final class ProvenanceAttestationStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> repositoryPrefixes() {
        return Set.of(ProvenanceAttestationCache.PREFIX);
    }
}
