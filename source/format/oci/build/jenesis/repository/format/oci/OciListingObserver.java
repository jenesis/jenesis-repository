package build.jenesis.repository.format.oci;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ListingObserver;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.ServableNames;

/**
 * Keeps the OCI {@linkplain OciListings stored tag lists and catalog} and the {@linkplain OciReferrers referrers
 * indexes} in step with the transitions that happen off the push path - a hold on a pushed manifest and its release, a
 * removal - by re-deciding the tag's membership (or every tag of the image, for a manifest addressed by digest) and,
 * for a referrer, its entry in its subject's index. A transition that names no image rebuilds every OCI listing in
 * place.
 */
public final class OciListingObserver implements ListingObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger(OciListingObserver.class);

    public OciListingObserver() {
    }

    @Override
    public void onMarked(ArtifactDescriptor subject, ArtifactStore store) {
        // A lifecycle mark changes nothing a Distribution client reads.
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
            if (OciFormat.isImageName(name)) {
                // The manifest a hold, a release or a removal is about, when it can be named: by digest, or by the
                // hash a removed pointer named. A referrer among them leaves or rejoins its subject's index.
                String hex = reference != null && reference.startsWith("sha256:") ? OciFormat.hex(reference)
                        : subject.hash();
                if (hex != null && ServableNames.isSha256Hex(hex)) {
                    new OciReferrers(store).refresh(name, hex);
                }
            }
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
