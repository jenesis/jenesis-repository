package build.jenesis.repository.format.raw;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.StoredListing;

import module java.base;

/**
 * Keeps the raw format's {@linkplain RawListings stored directory pages} in step with the transitions that happen
 * off the publish path - a hold on a file and its release, a removal - by re-deciding the one entry. A transition
 * that names no path rebuilds every page in place.
 */
public final class RawListingObserver implements PublicationObserver, StoredListing.Rebuilder {

    public RawListingObserver() {
    }

    @Override
    public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) {
        // The publish writes its own entries; nothing to do after the fact.
    }

    @Override
    public void onDeleted(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        transition(artifact, store);
    }

    @Override
    public void onWithheld(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        transition(subject, store);
    }

    @Override
    public void onWithholdCleared(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        transition(subject, store);
    }

    private void transition(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        if (subject.path() != null) {
            if (subject.path().startsWith("/raw/") && !subject.path().endsWith("/")) {
                new RawListings(store).refresh(subject.path());
            }
            return;
        }
        if (subject.ecosystem() != null && !subject.ecosystem().equals("raw")) {
            return;
        }
        if (store.list("publish/raw").isEmpty()) {
            return;
        }
        StoredListing.rebuildUnder(store, "raw/", this);
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return new RawListings(store).rebuild(listing);
    }
}
