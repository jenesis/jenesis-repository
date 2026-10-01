package build.jenesis.repository.format.winget;

import module java.base;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.BlobsListingObserver;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Keeps the winget {@linkplain WingetListings stored documents} in step with transitions off the publish path - a hold
 * and its release, a lifecycle mark and its reversal, a removal - by re-deciding the one version's membership of its
 * package list, which re-derives the package's index line. A transition naming only a content hash regenerates the
 * documents in place.
 */
public final class WingetListingObserver extends BlobsListingObserver {

    public WingetListingObserver() {
        super(new WingetFormat(), "winget", "winget listings");
    }

    @Override
    protected void refresh(Blobs blobs, ArtifactStore store, ArtifactDescriptor subject, ArtifactDescriptor named)
            throws IOException {
        for (String repo : blobs.list("winget")) {
            if (blobs.exists(WingetFormat.manifestKey(repo, named.coordinate(), named.version()))) {
                new WingetListings(blobs).refresh(repo, named.coordinate(), named.version());
            }
        }
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return new WingetListings(new Blobs(store)).rebuild(listing);
    }
}
