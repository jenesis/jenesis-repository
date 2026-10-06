package build.jenesis.repository.dependents;

import module java.base;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.walk.EndOfWalkConsumer;
import build.jenesis.repository.walk.WalkConsumer;
import build.jenesis.repository.walk.WalkPass;
import build.jenesis.repository.walk.WalkProvider;

/**
 * The reverse-dependency index reconciled at the end of a walk: the full rebuild that heals what the change feed
 * missed and compacts the feed, run when a walk carrying this consumer completes - weekly by default, on the rebuild
 * entry. The rebuild itself rides the index's own walk pass; this listens to the pointer stream only to learn which
 * store it serves.
 */
public final class DependentsRebuildConsumer extends EndOfWalkConsumer {

    /** The consumer's name: its toggle ({@code jenrepo.dependents-rebuild}) and how a walk entry names it. */
    public static final String NAME = "dependents-rebuild";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Rebuilds the reverse-dependency index from every stored SBOM at the end of the walk and compacts its change "
                + "feed; reads every blob that carries an SBOM over its own pass.";
    }

    @Override
    protected void atEnd(WalkPass pass, ArtifactStore store) throws IOException {
        new DependentsIndex(store, WalkProvider.resolve(Features.settings()).orElse(null)).reconcile();
    }
}
