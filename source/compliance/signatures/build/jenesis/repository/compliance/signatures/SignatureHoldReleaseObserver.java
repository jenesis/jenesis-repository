package build.jenesis.repository.compliance.signatures;

import module java.base;
import build.jenesis.repository.gate.HoldKind;
import build.jenesis.repository.gate.HoldReleaseObserver;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Joins the {@code signature} hold kind to the review surfaces, so its {@code holds/signature} records can be released:
 * a kind without an observer would hold forever. A plain adapter over {@link HoldKind}, since the gate and
 * {@link SignatureSweepTask} record the same reason tokens, which a release promotes into the override.
 */
public final class SignatureHoldReleaseObserver implements HoldReleaseObserver {

    /** The {@code holds/signature/} and {@code overrides/signature/} prefixes, and the kind name a finding declares. */
    static final HoldKind KIND = HoldKind.of("signature");

    @Override
    public void onReleased(ArtifactStore store, String path) throws IOException {
        KIND.onReleased(store, path);
    }

    @Override
    public void onDiscarded(ArtifactStore store, String path) throws IOException {
        KIND.onDiscarded(store, path);
    }

    @Override
    public boolean holds(ArtifactStore store, String path) throws IOException {
        return KIND.holds(store, path);
    }

    @Override
    public String kind() {
        return KIND.name();
    }
}
