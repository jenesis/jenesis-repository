package build.jenesis.repository.publication.contract.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.testkit.PublicationHookFixture;

import module java.base;

/**
 * The pre-commit hold-release archetype, modelled on downstream's {@code KevHold} / {@code LicenseHold} release
 * observers: on a reviewer's release it promotes this kind's retroactive hold record into an override marker, so a
 * later enforcement sweep never re-holds what a human has cleared; on a discard it drops the record and promotes
 * nothing, because no human cleared the finding.
 *
 * <p>It is deliberately <b>not</b> a {@code PublicationObserver}. Despite the name downstream gave the SPI, a
 * release hook is pre-commit and fail-closed: it propagates, and the release becomes visible only once every hook has
 * succeeded. Registering it as an observer would hand its failure to the contained after-commit path and release the
 * artifact anyway - which is what the kit's first check demonstrates rather than merely asserts.
 *
 * <p>Both legs are idempotent by construction, so the retry after a failed fan-out converges: the promotion is an
 * atomic-create (an already-promoted override is left alone) and the record delete is a no-op once the record is
 * gone. The record read is a {@code readVersioned}, so a store outage fails the release instead of reading as
 * "this kind held nothing".
 */
public final class OverrideHook implements PublicationHookFixture.ReleaseHook {

    /** The key space this hold kind owns. */
    public static final String SPACE = "kithold";

    /** One per-version record per held coordinate - what a retroactive sweep writes and a release consumes. */
    public static final String RECORDS = SPACE + "/records";

    /** One override marker per cleared coordinate - what stops the next sweep from re-holding it. */
    public static final String OVERRIDES = SPACE + "/overrides";

    @Override
    public void onReleased(ArtifactStore store, String path) throws IOException {
        if (store.readVersioned(RECORDS + "/" + Keys.slug(path)).isEmpty()) {
            return;    // this kind never held the path: a no-op, so registering an unused hold kind is harmless
        }
        // Atomic create: an override already promoted by an earlier attempt stays exactly as it was, which is what
        // makes a retry after a failed fan-out converge rather than compound.
        store.writeVersioned(OVERRIDES + "/" + Keys.slug(path), "cleared".getBytes(StandardCharsets.UTF_8), null);
        store.delete(RECORDS + "/" + Keys.slug(path));
    }

    @Override
    public void onDiscarded(ArtifactStore store, String path) throws IOException {
        // The record goes, so a thrown-away version's row does not dangle forever - but NO override is promoted: a
        // discarded version was never cleared by anyone, and an override would suppress a future legitimate hold.
        store.delete(RECORDS + "/" + Keys.slug(path));
    }
}
