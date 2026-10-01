package build.jenesis.repository.index;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;

/**
 * The published index's subscription to the withhold-change feed: it turns a withhold transition into the only thing
 * that retracts an immutable, consumer-cached index chunk - a rebuild of the chain. The withhold-face sibling of
 * {@code SearchPublicationObserver}. Both faces of a hold reach it: a {@code withheld/<hash>} marker from
 * {@code Withheld.mark} and a {@code /quarantine<servedPath>} review pointer from {@code Publication.link}.
 *
 * <p>It raises one coalescing dirty flag beside the {@link PublishedIndex} descriptor; the next
 * {@link PublishedIndexTask} pass reads it and runs a full rebase, which re-screens every path through
 * {@code ServableNames}, so a withheld path drops out and a cleared one - which sits below the watermark and no
 * incremental pass would re-append - comes back.
 *
 * <p>Marking is unconditional (with the pass off the flag is one inert object) and contained: this body swallows its
 * own {@link RuntimeException} so it can never fail {@code Withheld.mark} or {@code Publication.link}.
 *
 * <p><b>A lost signal is lost for good.</b> The withhold legs fire only on an actual transition, so a signal dropped
 * between the durable write and the notify is never re-emitted ({@link PublicationObserver} clause 11); the walk's
 * {@code index-rebase} consumer is then the only route back.
 */
public final class IndexRetractionObserver implements PublicationObserver {

    @Override
    public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) {
        // A publish needs no flag: the pass appends new publications and the append-time ServableNames screen omits
        // anything held.
    }

    @Override
    public void onWithheld(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        mark(store);
    }

    @Override
    public void onWithholdCleared(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        mark(store);
    }

    /** The subject is unused: the flag's presence is the whole signal, and the rebase it triggers re-derives from the
     *  store, so one marker retracts every alias with no per-hash bookkeeping. */
    private void mark(ArtifactStore store) throws IOException {
        try {
            new PublishedIndex(store).retraction().mark(Instant.now());
        } catch (RuntimeException contained) {
            // A withhold transition never fails on this consumer's account.
        }
    }
}
