package build.jenesis.repository.gate.store;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.gate.HoldReleaseObserver;
import build.jenesis.repository.gate.KevHold;

/**
 * The {@link HoldReleaseObserver} for the retroactive known-exploited (KEV) hold: on release it promotes any
 * {@link KevHold} record for the path's coordinate into the override marker, so the {@code kev-enforce} sweep never
 * re-holds a release for a CVE a human has cleared. A thin adapter over {@link KevHold#onReleased} so the review
 * surfaces discover the hook rather than naming {@code KevHold} directly.
 */
public final class KevHoldReleaseObserver implements HoldReleaseObserver {

    @Override
    public void onReleased(ArtifactStore store, String path) throws IOException {
        KevHold.onReleased(store, path);
    }

    @Override
    public void onDiscarded(ArtifactStore store, String path) throws IOException {
        KevHold.onDiscarded(store, path);
    }

    @Override
    public boolean holds(ArtifactStore store, String path) throws IOException {
        return KevHold.holds(store, path);
    }

    @Override
    public String kind() {
        return "kev";   // the holds/kev/ prefix KevHold keys its records by
    }
}
