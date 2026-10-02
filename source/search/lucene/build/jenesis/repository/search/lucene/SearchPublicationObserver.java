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
 * The after-commit hook that marks a publish or delete in the search index's {@link DirtyIndexFeed}, so the
 * {@link SearchIndexTask} applies only what changed. It rides the store's publish path, so every way an artifact is
 * published or removed feeds it. It only writes a small marker under {@code index/search/dirty/}, one per coordinate; a
 * failed write is contained by the store contract and never fails the upload.
 *
 * <p>Marking is gated on the index existing: a repository without full-text search holds none, and before the first
 * pass the bootstrap indexes everything from truth anyway. A publish carrying neither a coordinate nor a path-addressed
 * path indexes nothing.
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
        // A descriptor with no coordinate is a path-addressed artifact, indexed under its served path; one carrying
        // neither is a checksum or generated metadata.
        String key;
        if (artifact.coordinate() == null || artifact.version() == null) {
            // Asked of the format: a checksum beside a coordinate artifact also has no coordinate, and indexing every
            // one would repeat the coordinate document.
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

    /** Whether the repository holds an index to mark: one probe of its manifest, an absence remembered in the node's
     *  miss memory where the store has one, so a burst of publishes into a repository without search costs at most one
     *  probe per window. Another node's write is seen when the window lapses, and the bootstrap reads truth anyway. */
    private static boolean indexed(ArtifactStore store) throws IOException {
        Optional<MissMemory> misses = NodeMemoStore.misses(store);
        if (misses.isPresent() && misses.get().remembered(store, SearchIndex.MANIFEST)) {
            return false;
        }
        long mark = misses.map(MissMemory::mark).orElse(0L);
        if (new SearchIndex(store).exists()) {
            return true;
        }
        misses.ifPresent(memory -> memory.remember(store, SearchIndex.MANIFEST, mark));
        return false;
    }
}
