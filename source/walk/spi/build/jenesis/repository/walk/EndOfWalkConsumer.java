package build.jenesis.repository.walk;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A walk consumer whose work is one pass of its own over a repository, run when the walk over that repository
 * completes - a rebuild, a rebase, a roll-up - rather than a step per walked entry. It rides a repository from the
 * moment its pass starts, or from the first entry retained in it where a resumed pass reported no start, and runs
 * {@link #atEnd} once that pass completes; a repository it never rode is left alone.
 */
public abstract class EndOfWalkConsumer implements WalkConsumer {

    private final Set<Object> riding = ConcurrentHashMap.newKeySet();

    @Override
    public final void onRetained(ArtifactDescriptor artifact, ArtifactStore store) {
        riding.add(store.identity());
    }

    @Override
    public final void onPassStarted(WalkPass pass, ArtifactStore store) {
        riding.add(store.identity());
    }

    @Override
    public final void onPassCompleted(WalkPass pass, ArtifactStore store) {
        if (!riding.remove(store.identity())) {
            return;
        }
        try {
            atEnd(pass, store);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /** This consumer's own pass over the repository whose walk completed. */
    protected abstract void atEnd(WalkPass pass, ArtifactStore store) throws IOException;
}
