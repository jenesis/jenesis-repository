package build.jenesis.repository.format.nuget;

import module java.base;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.BlobsListingObserver;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;

/**
 * Keeps the NuGet {@linkplain NuGetListings stored documents} in step with the transitions that happen off the push
 * path - a hold on a pushed version and its release, a lifecycle mark and its reversal, a removal - by re-deciding
 * the one version's entries. A transition whose subject names neither a package path nor a coordinate is mapped to
 * no entry, so every document is rebuilt in place.
 */
public final class NuGetListingObserver extends BlobsListingObserver {

    public NuGetListingObserver() {
        super(new NuGetFormat(), "nuget", "NuGet documents");
    }

    @Override
    protected void refresh(Blobs blobs, ArtifactStore store, ArtifactDescriptor subject, ArtifactDescriptor named)
            throws IOException {
        String id = named.coordinate().toLowerCase(Locale.ROOT);
        if (!blobs.isEmpty("nuget/" + id + "/" + named.version())
                || StoredListing.present(store, NuGetListings.versions(id))) {
            new NuGetListings(blobs).refresh(id, named.version());
        }
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return new NuGetListings(new Blobs(store)).rebuild(listing);
    }
}
