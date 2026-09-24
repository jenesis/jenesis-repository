package build.jenesis.repository.compliance.web;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;

/**
 * Reclaims a cached provenance attestation when the served pointer it describes is unpublished - the content-keyed
 * counterpart of the per-version sidecar deletes {@code StoreRepositoryInventory.evict} performs on the coordinate-keyed
 * spaces. The {@link ProvenanceAttestationCache} keys each attestation by {@code (blob SHA-256, served path)}, so it
 * cannot be reached from a coordinate version; the one lifecycle its key aligns with is the pointer's own. A discovered
 * {@code PublicationObserver} therefore rebuilds the exact cache key from every {@code onDeleted} descriptor - whose
 * blob identity the eviction primitive completes from the pointer - and deletes it, so an evicted (or otherwise
 * unpublished) artifact leaves no attestation behind. The reaper lives in the same module that writes the cache, so
 * the space is reaped exactly where it is populated (the provenance endpoint), and stays declared-and-purge-visible
 * everywhere else through {@link ProvenanceAttestationStorageNamespace}.
 *
 * <p>Best-effort by design: the attestation is derived data, so a delete that races a concurrent read (or fails on a
 * transient store error) costs at most one re-sign on the next request, never correctness - the reaper logs and moves
 * on rather than failing the eviction that notified it. A descriptor with no completed blob hash (a pointer whose blob
 * identity could not be resolved) is skipped: without the content half of the key there is nothing to reclaim here.
 */
public final class ProvenanceAttestationReaper implements PublicationObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProvenanceAttestationReaper.class);

    @Override
    public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) {
        // Publishing mints no attestation - the ProvenanceController does that lazily on first read - so nothing to do.
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
