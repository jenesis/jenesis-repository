package build.jenesis.repository.gate.store;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.gate.HoldReleaseObserver;
import build.jenesis.repository.gate.ManualHold;

/**
 * The {@link HoldReleaseObserver} for a hold an operator placed by hand: a release or a discard consumes the
 * {@link ManualHold} record of the path's coordinate version. A thin adapter so the review surfaces discover the hook
 * rather than naming {@code ManualHold} directly.
 */
public final class ManualHoldReleaseObserver implements HoldReleaseObserver {

    @Override
    public void onReleased(ArtifactStore store, String path) throws IOException {
        ManualHold.onReleased(store, path);
    }

    @Override
    public void onDiscarded(ArtifactStore store, String path) throws IOException {
        ManualHold.onDiscarded(store, path);
    }

    @Override
    public boolean holds(ArtifactStore store, String path) throws IOException {
        return ManualHold.holds(store, path);
    }

    @Override
    public String kind() {
        return "manual";   // the holds/manual/ prefix ManualHold keys its records by
    }
}
