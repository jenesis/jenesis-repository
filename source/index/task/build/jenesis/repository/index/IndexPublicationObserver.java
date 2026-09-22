package build.jenesis.repository.index;

import module java.base;

import build.jenesis.repository.index.keys.PublishedIndexKeys;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.DirtyIndexFeed;
import build.jenesis.repository.store.PublicationObserver;

/**
 * The published index's write-path half: every publish marks its request path in the index's dirty feed, and the
 * scheduled pass appends exactly the marked paths - the paths published since its last run, however many there are,
 * never a walk of the publish tree to find them. A mark that never lands (the process died between the pointer and
 * this callback) is healed by the rebase a walk of the store carries ({@link IndexRebaseConsumer}), which re-derives
 * the whole chain from the pointers and compacts the feed. Best-effort and repaired, like every after-commit note.
 */
public final class IndexPublicationObserver implements PublicationObserver {

    @Override
    public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        new DirtyIndexFeed(store, PublishedIndexKeys.PREFIX).touched(artifact.path(), System.currentTimeMillis());
    }
}
