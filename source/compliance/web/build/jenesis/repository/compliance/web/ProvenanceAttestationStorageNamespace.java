package build.jenesis.repository.compliance.web;

import module java.base;
import build.jenesis.repository.maintenance.StorageNamespace;

/**
 * The provenance surface's storage manifest: it owns the per-repository {@code provenance-attestation} key-space -
 * the content-addressed cache of signed attestations {@link ProvenanceAttestationCache} keeps so an artifact is
 * signed and transparency-log-appended exactly once ({@link ProvenanceController}) rather than on every read. The
 * cache is keyed by {@code (blob SHA-256, served path)}, not by coordinate version, so it cannot ride the inventory's
 * per-version {@code evict}; its reclamation is the {@link ProvenanceAttestationReaper} - a discovered
 * {@code PublicationObserver} that deletes an artifact's cached attestation the moment that served pointer is
 * unpublished, the one lifecycle its content key aligns with. Declaring the space here ends its
 * purge-invisibility: before this it was written by no declared namespace, so the orphan diagnostic and the operator
 * purge could not see it. Derived data by design - a lost or stale entry only costs one re-sign on the next read.
 */
public final class ProvenanceAttestationStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> repositoryPrefixes() {
        return Set.of(ProvenanceAttestationCache.PREFIX);
    }
}
