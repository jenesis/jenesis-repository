package build.jenesis.repository.format.oci;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.StoredListing;

import module java.base;

/**
 * Keeps the OCI {@linkplain OciListings stored tag lists and catalog} in step with the transitions that happen off
 * the push path - a hold on a pushed manifest and its release, a removal - by re-deciding the tag's membership (or
 * every tag of the image, for a manifest addressed by digest). A transition that names no image forgets every OCI
 * listing, so they regenerate on their next read.
 */
public final class OciListingObserver implements PublicationObserver, StoredListing.Rebuilder {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(OciListingObserver.class);

    public OciListingObserver() {
    }

    @Override
    public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) {
        // The push writes its own entry; nothing to do after the fact.
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
        if (subject.ecosystem() != null && !subject.ecosystem().equals("oci")) {
            return;
        }
        String name = subject.coordinate();
        String reference = subject.version();
        if (name == null && subject.path() != null) {
            int manifests = subject.path().indexOf("/manifests/");
            if (!subject.path().startsWith("/v2/") || manifests < 0) {
                return;   // a path of another format's, or an OCI path that names no manifest
            }
            name = subject.path().substring("/v2/".length(), manifests);
            reference = subject.path().substring(manifests + "/manifests/".length());
        }
        if (name != null) {
            if (store.list("oci/" + name + "/tags").isEmpty()) {
                return;
            }
            OciListings listings = new OciListings(store);
            if (reference != null && !reference.startsWith("sha256:")) {
                listings.refresh(name, reference);
            } else {
                listings.refreshImage(name);
            }
            return;
        }
        if (store.list("oci").isEmpty()) {
            return;
        }
        LOGGER.info("OCI listings regenerated in place: a hold transition named only a content hash");
        StoredListing.rebuildUnder(store, "oci/", this);
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return new OciListings(store).rebuild(listing);
    }
}
