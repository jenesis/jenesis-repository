package build.jenesis.repository.search.lucene;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.DirtyIndexFeed;
import build.jenesis.repository.store.MissMemory;
import build.jenesis.repository.store.NodeMemoStore;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.ServableNames;

/**
 * The after-commit hook that routes a publish or a delete into the search index's {@link DirtyIndexFeed}, so the
 * scheduled {@link SearchIndexTask} sweep applies only what changed (O(&Delta;)) instead of re-deriving the whole
 * coordinate set. Discovered on the free {@code store} publish path like any {@link PublicationObserver} (the
 * interceptors and observers are the one {@code PublicationObserver} service now), so a format's own deploy, a staging
 * promotion, a proxied caching publish and a layout-aware eviction all feed it without this module touching a format.
 * It only leaves a note - a small dirty marker under the index's own {@code index/search/dirty/} space, coalesced onto
 * one marker per coordinate - and the sweep does the indexing; a marker write never fails the upload or blocks the
 * removal (an observer's failure is logged and contained by the store contract).
 *
 * <p>Marking is gated on the index existing: a repository with full-text search off holds none and is never marked,
 * and until the first pass has built a manifest a publish is not marked either, because that first pass is a full
 * rebuild (bootstrap) that indexes every published coordinate from truth anyway. Once the index exists, every publish
 * and delete is marked, and the incremental pass applies it; the reconcile GCs the feed and heals anything that
 * slipped through.
 * A publish carrying no coordinate (a checksum, generated metadata) indexes nothing, so it is skipped.
 */
public final class SearchPublicationObserver implements PublicationObserver {

    @Override
    public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        mark(artifact, store, false);
    }

    @Override
    public void onDeleted(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        mark(artifact, store, true);
    }

    private void mark(ArtifactDescriptor artifact, ArtifactStore store, boolean removed) throws IOException {
        if (!indexed(store)) {
            return;   // no index here - off, or not built yet, when the bootstrap indexes everything from truth
        }
        DirtyIndexFeed feed = new DirtyIndexFeed(store, SearchIndex.DIRECTORY);
        // A descriptor with no coordinate is a PATH-ADDRESSED artifact - a raw upload - and it is indexed under its
        // served path. This used to return here, which is why search had to find them by walking the store on every
        // request instead. A descriptor carrying neither is a checksum or generated metadata and is still nothing
        // the index carries.
        String key;
        if (artifact.coordinate() == null || artifact.version() == null) {
            // Asked of the FORMAT, not of this descriptor's empty coordinate: a checksum published beside a
            // coordinate-addressed artifact also arrives with no coordinate, and indexing every one of those would
            // put four or five documents in the index per artifact to say what the coordinate document already
            // says. Path-addressed means the format serving it has no coordinate concept at all.
            if (artifact.path() == null || !new StoreRepositoryInventory(store).pathAddressed(artifact.path())) {
                return;
            }
            key = SearchIndexTask.pathKey(artifact.path());
        } else {
            key = SearchIndexTask.coordinateKey(artifact.ecosystem(), artifact.coordinate(), artifact.version());
        }
        long version = Instant.now().toEpochMilli();   // the change's time - the sweep's out-of-order guard orders by it
        if (removed) {
            feed.removed(key, version);
        } else {
            feed.touched(key, version);
        }
    }

    /**
     * Whether the repository holds an index to mark - one existence probe of its manifest, remembered as an absence
     * in the node's miss memory where the store has one. A repository with full-text search off holds none, so a burst
     * of publishes into it - a build deploying its dozens of files - costs at most one probe per memory window rather
     * than one per file. The memory forgets the key when this node writes it and lapses within its short window when
     * another node does, which only delays marking a publish that the bootstrap building that index reads from truth
     * anyway.
     */
    private static boolean indexed(ArtifactStore store) throws IOException {
        Optional<MissMemory> misses = NodeMemoStore.misses(store);
        if (misses.isPresent() && misses.get().remembered(store, SearchIndex.MANIFEST)) {
            return false;
        }
        if (new SearchIndex(store).exists()) {
            return true;
        }
        misses.ifPresent(memory -> memory.remember(store, SearchIndex.MANIFEST));
        return false;
    }
}
