package build.jenesis.repository.format.cocoapods;

import module java.base;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.BlobsListingObserver;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Keeps the CocoaPods {@linkplain CocoaPodsListings stored shard listings} in step with transitions off the publish
 * path - a hold and its release, a yank and its reversal, a removal - by re-deciding the one version. A subject naming
 * neither a pod path nor a coordinate regenerates every listing in place.
 */
public final class CocoaPodsListingObserver extends BlobsListingObserver {

    public CocoaPodsListingObserver() {
        super(new CocoaPodsFormat(), "cocoapods", "CocoaPods shard listings");
    }

    @Override
    protected void refresh(Blobs blobs, ArtifactStore store, ArtifactDescriptor subject, ArtifactDescriptor named)
            throws IOException {
        for (String repo : blobs.list("cocoapods")) {
            if (blobs.exists(CocoaPodsFormat.blobKey(repo, named.coordinate(), named.version()))) {
                new CocoaPodsListings(blobs).refresh(repo, named.coordinate(), named.version());
            }
        }
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return new CocoaPodsListings(new Blobs(store)).rebuild(listing);
    }
}
