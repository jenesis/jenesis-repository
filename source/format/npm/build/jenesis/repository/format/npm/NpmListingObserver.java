package build.jenesis.repository.format.npm;

import module java.base;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.BlobsListingObserver;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;

/**
 * Keeps the npm {@linkplain NpmListings stored packuments} in step with transitions off the publish path - a hold and
 * its release, a lifecycle mark and its reversal, a removal - by re-deciding the one version's entry. A subject naming
 * neither a tarball path nor a coordinate rebuilds every packument in place.
 */
public final class NpmListingObserver extends BlobsListingObserver {

    public NpmListingObserver() {
        super(new NpmFormat(), "npm", "npm packuments");
    }

    @Override
    protected void refresh(Blobs blobs, ArtifactStore store, ArtifactDescriptor subject, ArtifactDescriptor named)
            throws IOException {
        if (blobs.exists("npm/" + named.coordinate() + "/versions/" + named.version())
                || StoredListing.present(store, NpmListings.packument(named.coordinate()))) {
            new NpmListings(blobs).refresh(named.coordinate(), named.version());
        }
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return new NpmListings(new Blobs(store)).rebuild(listing);
    }
}
