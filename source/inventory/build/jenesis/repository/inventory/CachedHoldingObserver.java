package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Clocks;
import build.jenesis.repository.store.PublicationObserver;

/**
 * Records what a pull-through caches as a holding of the repository, from the fill's own notice
 * ({@link PublicationObserver#onCached}): the version the owning format describes the path to, the upstream it came
 * from and when - through {@link StoreRepositoryInventory#cache(ArtifactDescriptor, URI, Instant)}, so a copy is
 * recorded once, never over a release, and never for a fill that stored nothing.
 *
 * <p>Every fill reaches this one point, whichever route fetched it - a router fallback, hardened or not, or a format's
 * upstream behind the dispatcher - because the pull-through loop they all run fires it. A fill the screen held or
 * refused serves nothing and is not reported, so nothing is recorded for it.
 *
 * <p><b>The two-route contract</b> ({@link PublicationObserver}): this is the live-event route, and it is
 * best-effort - a lost record never fails the serve. The walk is the other route: the reconcile and the inventory
 * back-fill read what every served pointer's document records and record the copy from the fill's origin trail
 * wherever this notice was lost or never sent.
 */
public final class CachedHoldingObserver implements PublicationObserver {

    /** Nothing: a fill's publish notice is the same notice a release gets, and what tells the two apart is
     *  {@link #onCached}, which follows it. */
    @Override
    public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) {
    }

    @Override
    public void onCached(ArtifactDescriptor artifact, URI upstream, ArtifactStore store) throws IOException {
        if (artifact.path() == null) {
            return;
        }
        new StoreRepositoryInventory(store).cache(artifact, upstream, Clocks.now());
    }
}
