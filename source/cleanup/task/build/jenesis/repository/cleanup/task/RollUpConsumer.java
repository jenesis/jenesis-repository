package build.jenesis.repository.cleanup.task;

import module java.base;

import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.WalkConsumer;
import build.jenesis.repository.walk.WalkPass;

/**
 * The browse's per-folder subtree sizes rolled up at the end of a walk: when a pass carrying this consumer
 * completes over a repository, the inventory recomputes the folder totals from the tree - the post-order fold on
 * its own {@code walks/rollup} pass, whose per-segment partials are bound to that pass's segments, which is why
 * it still opens one. The publish observers keep the totals incrementally; this is the repair, riding the
 * {@code retention} walk daily by default instead of every hour.
 */
public final class RollUpConsumer implements WalkConsumer {

    /** The consumer's name: its walk-entry name and its toggle ({@code jenreg.rollup}). */
    public static final String NAME = "rollup";

    private final Set<Object> riding = ConcurrentHashMap.newKeySet();

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
    public void onRetained(ArtifactDescriptor artifact, ArtifactStore store) {
        riding.add(store.identity());
    }

    @Override
    public void onPassStarted(WalkPass pass, ArtifactStore store) {
        riding.add(store.identity());
    }

    @Override
    public void onPassCompleted(WalkPass pass, ArtifactStore store) {
        if (!riding.remove(store.identity())) {
            return;
        }
        try {
            new StoreRepositoryInventory(store).rollUpSizes();
        } catch (IOException unrolled) {
            throw new UncheckedIOException(unrolled);
        }
    }
}
