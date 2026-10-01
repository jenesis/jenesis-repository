package build.jenesis.repository.compliance.web;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;

/**
 * Reclaims a cached provenance attestation when its served pointer is unpublished: keyed by blob and path, it follows
 * the pointer's lifecycle rather than a version's, so this observer rebuilds the key from each {@code onDeleted}
 * descriptor and deletes it. Best-effort, since a lost delete costs one re-sign; a descriptor without a blob hash is
 * skipped, and {@link ProvenanceAttestationSweep} clears what this misses.
 */
public final class ProvenanceAttestationReaper implements PublicationObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProvenanceAttestationReaper.class);

    @Override
    public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) {
        // Attestations are minted lazily on first read.
    }

    @Override
    public void onDeleted(ArtifactDescriptor artifact, ArtifactStore store) {
        String sha256 = artifact.hash();
        String path = artifact.path();
        if (sha256 == null || sha256.length() != 64 || path == null || path.isEmpty()) {
            return;    // no content-addressed key to rebuild without the completed blob hash and its served path
        }
        String key = ProvenanceAttestationCache.key(sha256, path);
        try {
            if (store.readVersioned(key).isPresent()) {
                store.delete(key);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not reclaim the provenance attestation {} for unpublished {}; a stale cache entry lingers "
                    + "until the operator purge, and the next read re-signs", key, path, e);
        }
    }
}
