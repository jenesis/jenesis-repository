package build.jenesis.repository.compliance.signatures;

import module java.base;
import build.jenesis.repository.gate.HoldKind;
import build.jenesis.repository.gate.HoldReleaseObserver;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Joins the {@code signature} hold kind to the review surfaces, so a hold this dimension places can be lifted.
 *
 * <h2>Why a dimension that declares a hold kind must also declare this</h2>
 *
 * A quarantining finding that names a hold kind makes the screen write a durable {@code holds/<kind>} record
 * ({@code ComplianceScreen.recordHolds}), and every release surface clears such a record through the discovered
 * {@link HoldReleaseObserver} for that kind. A kind with no observer therefore writes records nothing can consume:
 * the review queue renders the row as held by an uninstalled kind, {@code HoldReleaseObserver.anyHolds} keeps
 * answering yes forever, and an accepted re-publish of the same path declines to clear the quarantine pointer because
 * it reads the coordinate as sweep-owned. Every one of those is the product working exactly as designed around a
 * declaration that was only half made.
 *
 * <p>It is a plain adapter over {@link HoldKind} rather than a class of its own like {@code LicenseHold}: those exist
 * because a retroactive sweep recovers its subjects from elsewhere, and this dimension's sweep
 * ({@link SignatureSweepTask}) records the same tokens the gate does. The subjects recorded are the finding's own
 * reason tokens, and an operator's release promotes them into the sticky override the same way, so the sweep finds
 * a release it must not undo.
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
