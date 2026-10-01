package build.jenesis.repository.index;

import module java.base;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.walk.WalkConsumer;
import build.jenesis.repository.walk.WalkPass;

/**
 * The published index rebased at the end of a walk: a fresh chunk chain re-derived from every served pointer, in path
 * order. The pass keeps the rebases an event demands - no chain yet, a chain with a hole, a standing retraction flag -
 * and appends only what the dirty feed marked; this consumer is the scheduled rebase, weekly on the rebuild entry by
 * default. It enumerates the pointers over its own ordered walk at completion, because a chain is committed whole and a
 * segmented pass cannot hand it over in order; it listens on the pointer stream only to learn which store's walk it
 * rides.
 *
 * <p>A chunk is immutable and cached by consumers, so this rebase is also what removes a retroactively withheld path
 * once the retraction flag was missed; carrying this consumer on no entry leaves the flag alone for that.
 */
public final class IndexRebaseConsumer implements WalkConsumer {

    /** The consumer's name: its toggle ({@code jenrepo.index-rebase}) and how a walk entry names it. */
    public static final String NAME = "index-rebase";

    private final Set<Object> riding = ConcurrentHashMap.newKeySet();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Rebases the published index onto a fresh chunk chain from every served pointer at the end of the "
                + "walk and compacts its change feed; reads every pointer and its inventory record over its own "
                + "ordered pass, and is what retracts a withheld path a missed flag left published.";
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
            new PublishedIndexTask(Duration.ZERO, PublishedIndexTaskProvider.maxChunk(Features.settings()))
                    .rebase(store, Instant.now());
        } catch (IOException unrebased) {
            throw new UncheckedIOException(unrebased);
        }
    }
}
