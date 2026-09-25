package build.jenesis.repository.gate.store;

import module java.base;

import build.jenesis.repository.gate.HoldReleaseObserver;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The review side of the compliance hold, over one repository's store. The publish-path screening itself rides the
 * publication-interceptor chain (the {@code gate} module's {@code ComplianceScreen}): a quarantined
 * upload is stored content-addressed but pointed at only under the {@code /quarantine} view, where it does not
 * resolve - and the same screen withholds the path from serving while the hold is pending. What remains here is
 * what a reviewer does with such a hold: {@link #release} promotes the held bytes into the release layout by
 * re-pointing the same blob (never copying it) and clears the hold, and {@link #discard} drops the hold without
 * publishing. Both go through the {@link build.jenesis.repository.store.Publication}, so the review operations work over any
 * format's store.
 */
public final class GatedRepository {

    private final ArtifactStore store;

    private final Iterable<HoldReleaseObserver> hooks;

    public GatedRepository(ArtifactStore store) {
        this(store, HoldReleaseObserver.discovered());
    }

    /** A review surface whose release and discard fan out to {@code hooks} - the substitution seam a contract over
     *  one hold-release hook drives the real choreography through. */
    public GatedRepository(ArtifactStore store, Iterable<HoldReleaseObserver> hooks) {
        this.store = store;
        this.hooks = hooks;
    }

    /** Promote a previously quarantined path into the release layout after review, through the shared
     *  {@link HoldLifecycle} primitive - the same implementation the console review surface uses, so the two can
     *  never disagree on crash-window ordering (override markers durable before the hold pointer clears, the
     *  release pointer linked only when absent so a corrected republish is never rolled back to the held bytes). */
    public void release(String path) throws IOException {
        HoldLifecycle.release(store, path, hooks);
    }

    /** Discard a quarantined path without releasing it, through the shared {@link HoldLifecycle} primitive: the
     *  log rows, findings document and {@code holds/} records are reaped, and a retroactive hold's still-held release
     *  pointer is evicted so the discarded artifact does not resume serving. Answers whether anything was held: a
     *  stale discard strips no served version's history and is reported as having discarded nothing - the same answer
     *  the console gives - rather than raised, so both surfaces tell the reviewer the same thing. */
    public boolean discard(String path) throws IOException {
        return HoldLifecycle.discard(store, path, hooks);
    }
}
