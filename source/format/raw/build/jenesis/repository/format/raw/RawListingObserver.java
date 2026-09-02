package build.jenesis.repository.format.raw;

import module java.base;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ListingObserver;
import build.jenesis.repository.store.StoredListing;

/**
 * Keeps the raw format's {@linkplain RawListings stored directory pages} in step with the transitions that happen
 * off the publish path - a hold on a file and its release, a removal - by re-deciding the one entry. A transition
 * that names no path rebuilds every page in place.
 */
public final class RawListingObserver implements ListingObserver {

    public RawListingObserver() {
    }

    @Override
    public void onMarked(ArtifactDescriptor subject, ArtifactStore store) {
        // The raw layout carries no lifecycle module: a mark changes nothing a directory listing shows.
    }

    @Override
    public void transition(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        if (subject.path() != null) {
            if (subject.path().startsWith("/raw/") && !subject.path().endsWith("/")) {
                new RawListings(store).refresh(subject.path());
            }
            return;
        }
        if (subject.ecosystem() != null && !subject.ecosystem().equals("raw")) {
            return;
        }
        if (store.isEmpty("publish/raw")) {
            return;
        }
        StoredListing.rebuildUnder(store, "raw/", this);
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return new RawListings(store).rebuild(listing);
    }
}
