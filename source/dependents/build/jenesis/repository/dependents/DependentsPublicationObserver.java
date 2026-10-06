package build.jenesis.repository.dependents;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.DirtyIndexFeed;
import build.jenesis.repository.store.PublicationObserver;

/**
 * The after-commit hook that marks a published or deleted blob in the reverse-dependency index's
 * {@link DirtyIndexFeed}, so the {@link DependentsIndexTask} sweep applies only what changed. It leaves one coalesced
 * marker per blob hash under {@code dependents/dirty/}; a marker write never fails the publish or the removal.
 *
 * <p>Keyed by blob hash, because the graph is derived from each blob's embedded SBOM, not from the pointers. Marking
 * waits until the index is built ({@link DependentsStore#BUILT}): the first sweep inverts every blob anyway, so a
 * deployment that never built the index accumulates no markers. A descriptor with no blob hash contributes no edge and
 * is skipped.
 */
public final class DependentsPublicationObserver implements PublicationObserver {

    @Override
    public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        mark(artifact, store, false);
    }

    @Override
    public void onDeleted(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        mark(artifact, store, true);
    }

    private void mark(ArtifactDescriptor artifact, ArtifactStore store, boolean removed) throws IOException {
        String hash = artifact.hash();
        if (hash == null || hash.isBlank()) {
            return;   // no blob hash - nothing the reverse-dependency graph is keyed by
        }
        if (!store.exists(DependentsStore.BUILT)) {
            return;   // no index built yet - the bootstrap full rebuild inverts every blob, so no marker is needed
        }
        DirtyIndexFeed feed = new DirtyIndexFeed(store, DependentsStore.PREFIX);
        long version = Instant.now().toEpochMilli();   // the change's time - the reconcile's cutoff orders by it
        if (removed) {
            feed.removed(hash, version);
        } else {
            feed.touched(hash, version);
        }
    }
}
