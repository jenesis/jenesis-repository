package build.jenesis.repository.cleanup.task;

import module java.base;

import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.EndOfWalkConsumer;
import build.jenesis.repository.walk.WalkConsumer;
import build.jenesis.repository.walk.WalkPass;

/**
 * The browse's per-folder subtree sizes rolled up at the end of a walk: when a pass carrying this consumer completes
 * over a repository, the inventory recomputes the folder totals in a post-order fold on its own {@code walks/rollup}
 * pass, whose per-segment partials are bound to that pass's segments. The publish observers keep totals incrementally;
 * this is the repair, on the {@code retention} entry, daily by default.
 */
public final class RollUpConsumer extends EndOfWalkConsumer {

    /** The consumer's name: its walk-entry name and its toggle ({@code jenrepo.rollup}). */
    public static final String NAME = "rollup";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Recomputes the cached folder sizes the browse screen shows, at the end of the walk over its own pass of the "
                + "pointers; reads every pointer and writes one small object per folder.";
    }

    @Override
    protected void atEnd(WalkPass pass, ArtifactStore store) throws IOException {
        new StoreRepositoryInventory(store).rollUpSizes();
    }
}
