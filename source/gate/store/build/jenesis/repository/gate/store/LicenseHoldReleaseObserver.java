package build.jenesis.repository.gate.store;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.gate.HoldReleaseObserver;
import build.jenesis.repository.gate.LicenseHold;

/**
 * The {@link HoldReleaseObserver} for the retroactive license hold: on release it promotes any {@link LicenseHold}
 * record for the path's coordinate into the override marker, so the {@code license-retro-enforce} sweep never re-holds
 * a release for a license reason a human has cleared. A thin adapter over {@link LicenseHold#onReleased} so the review
 * surfaces discover the hook rather than naming {@code LicenseHold} directly.
 */
public final class LicenseHoldReleaseObserver implements HoldReleaseObserver {

    @Override
    public void onReleased(ArtifactStore store, String path) throws IOException {
        LicenseHold.onReleased(store, path);
    }

    @Override
    public void onDiscarded(ArtifactStore store, String path) throws IOException {
        LicenseHold.onDiscarded(store, path);
    }

    @Override
    public boolean holds(ArtifactStore store, String path) throws IOException {
        return LicenseHold.holds(store, path);
    }

    @Override
    public String kind() {
        return "license";   // the holds/license/ prefix LicenseHold keys its records by
    }
}
