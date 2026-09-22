package build.jenesis.repository.format.conda;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ListingObserver;
import build.jenesis.repository.store.StoredListing;

/**
 * Keeps the conda {@linkplain CondaListings stored repodata} in step with the transitions that happen off the publish
 * path - a hold on a published package and its release, a yank and its reversal, a removal - by re-deciding the one
 * record's membership. A transition whose subject names neither a package path nor a conda coordinate is mapped to
 * no record, so the repository's repodata documents are regenerated in place.
 */
public final class CondaListingObserver implements ListingObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger(CondaListingObserver.class);

    private final CondaFormat format = new CondaFormat();

    public CondaListingObserver() {
    }

    @Override
    public void transition(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        if (subject.ecosystem() != null && !subject.ecosystem().equals(format.ecosystem())) {
            return;
        }
        Blobs blobs = new Blobs(store);
        CondaListings listings = new CondaListings(blobs);
        if (subject.path() != null) {
            if (subject.path().startsWith("/conda/")) {
                refresh(listings, subject.path().substring("/conda/".length()));
            }
            return;
        }
        if (subject.coordinate() != null && subject.version() != null) {
            for (String key : format.blobKeys(subject.coordinate(), subject.version(), store)) {
                refresh(listings, key.substring("conda/".length()).replace("/pkgs/", "/"));
            }
            return;
        }
        if (blobs.isEmpty("conda")) {
            return;
        }
        LOGGER.info("conda repodata regenerated in place: a hold transition named only a content hash");
        StoredListing.rebuildUnder(store, "conda/", this);
    }

    /** Re-decide the record of {@code <repo>/<subdir>/<file>}. */
    private static void refresh(CondaListings listings, String rest) throws IOException {
        String[] segments = rest.split("/");
        if (segments.length != 3) {
            return;
        }
        listings.refresh(segments[0], segments[1], segments[2]);
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return new CondaListings(new Blobs(store)).rebuild(listing);
    }
}
