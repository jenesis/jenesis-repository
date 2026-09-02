package build.jenesis.repository.format.oci;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ListingObserver;
import build.jenesis.repository.store.StoredListing;

/**
 * Keeps the OCI {@linkplain OciListings stored tag lists and catalog} in step with the transitions that happen off
 * the push path - a hold on a pushed manifest and its release, a removal - by re-deciding the tag's membership (or
 * every tag of the image, for a manifest addressed by digest). A transition that names no image rebuilds every OCI
 * listing in place.
 */
public final class OciListingObserver implements ListingObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger(OciListingObserver.class);

    public OciListingObserver() {
    }

    @Override
    public void onMarked(ArtifactDescriptor subject, ArtifactStore store) {
        // This format does not carry the lifecycle module: a mark changes nothing a Distribution client reads from a
        // tag list or an image index.
    }

    @Override
    public void transition(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
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
            if (store.isEmpty("oci/" + name + "/tags")) {
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
        if (store.isEmpty("oci")) {
            return;
        }
        LOGGER.info("OCI listings regenerated in place: a hold transition named only a content hash");
        StoredListing.rebuildUnder(store, "oci/", this);
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return new OciListings(store).rebuild(listing);
    }

    @Override
    public int materialise(ArtifactStore store, StoredListing.Rebuilder.Scope scope) throws IOException {
        return new OciListings(store).materialise(scope);
    }
}
