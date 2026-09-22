package build.jenesis.repository.index;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;

/**
 * The published index's subscription to the withhold-change feed (invariant (b) MATERIALIZATION): the after-commit
 * hook that turns a withhold transition into the one thing that can actually retract an immutable, content-addressed,
 * {@code Cache-Control: immutable} index chunk consumers have already cached - a rebuild of the chain. The exemplar is
 * {@code SearchPublicationObserver}, which routes {@code onPublished}/{@code onDeleted} into a {@code DirtyIndexFeed};
 * this is its withhold-face sibling. Discovered on the {@code store} publish path like any
 * {@link PublicationObserver}, so both faces of a hold reach it by construction - a {@code withheld/<hash>} marker
 * written by {@code Withheld.mark} (the KEV/license/OCI sweeps) and a fresh {@code /quarantine<servedPath>} review
 * pointer linked by {@code Publication.link} (every retroactive sweep, the reachability pointer-only hold included).
 *
 * <p>It records exactly one coalescing dirty flag in the module's own storage namespace (a sibling of the
 * {@link PublishedIndex} descriptor key), and the scheduled {@link PublishedIndexTask} pass does the retraction: it
 * reads the flag before its walk and, when present, forces the full rebase the module already runs on a schedule or a
 * broken chain, which re-screens every path through the servable-name seam - so a now-withheld path drops out of the
 * rebuilt chain and a cleared one re-appears (the mirror gap: a cleared version sits below the committed watermark, so
 * no incremental pass would ever re-append it). Both directions close within one index-pass interval.
 *
 * <p>Marking is unconditional (no index-exists gate: with the pass disabled the flag is one inert tiny object, simpler
 * than the search index's manifest gate and always safe) and defensive: a withhold transition must never fail because
 * this consumer's flag write threw - the feed already logs-and-contains an observer's exceptions
 * ({@code Publication.notifyWithheld}), and this body contains its own {@link RuntimeException} too so a programming
 * error here can never ride back into {@code Withheld.mark} / {@code Publication.link} and block a hold. A lost signal
 * (a crash between the durable write and this notify) is healed by the pass's own periodic rebuild-from-truth, the
 * crash/miss backstop of the two-route derived-metadata contract - worst case identical to today's exposure.
 *
 * <p><b>A lost signal is lost for good, which is why the backstop's cadence has a maximum.</b> The withhold legs fire
 * only on an actual transition, so a replay's re-mark or re-link is an idempotent converge and raises nothing - a
 * signal dropped in the durable-write-to-notify window is never re-emitted ({@link PublicationObserver} clause 11,
 *). This flag is therefore best-effort in the strong sense: when it is set the retraction lands on the next pass,
 * and when it is missed the only route back is {@code PublishedIndexTask}'s wall-clock rebase. That cadence
 * (the walk's {@code index-rebase} consumer) is consequently what carries it when the flag is missed.
 */
public final class IndexRetractionObserver implements PublicationObserver {

    @Override
    public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) {
        // A publish is not a withhold transition and needs no flag: the pass indexes new publications incrementally,
        // and the append-time ServableNames screen already omits anything held. Only the retraction faces below act.
    }

    @Override
    public void onWithheld(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        mark(store);
    }

    @Override
    public void onWithholdCleared(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        mark(store);
    }

    /** The subject is deliberately unused: PRESENCE of the coalescing flag is the whole signal, and the rebase it
     *  triggers re-derives from truth, resolving aliasing exactly (one marker retracts every alias) with no per-hash
     *  or per-path bookkeeping to keep. */
    private void mark(ArtifactStore store) throws IOException {
        try {
            new PublishedIndex(store).retraction().mark(Instant.now());
        } catch (RuntimeException contained) {
            // Never let a withhold transition fail on this consumer's account (see the class javadoc).
        }
    }
}
