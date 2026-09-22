package build.jenesis.repository.format.rpm;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ListingObserver;
import build.jenesis.repository.store.StoredListing;

/**
 * Keeps the RPM {@linkplain RpmListings stored metadata} in step with the transitions that happen off the publish
 * path - a hold on a published package and its release, a yank and its reversal, a removal - by re-deciding the one
 * stanza's membership. A transition whose subject names neither a pool path nor an RPM coordinate is mapped to no
 * stanza, so every repository's metadata is rebuilt in place.
 */
public final class RpmListingObserver implements ListingObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger(RpmListingObserver.class);

    private final RpmFormat format = new RpmFormat();

    public RpmListingObserver() {
    }

    @Override
    public void transition(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        if (subject.ecosystem() != null && !subject.ecosystem().equals(format.ecosystem())) {
            return;
        }
        Blobs blobs = new Blobs(store);
        RpmListings listings = format.listings(blobs);
        if (subject.path() != null) {
            if (subject.path().startsWith("/rpm/") && subject.path().endsWith(".rpm")) {
                refresh(listings, subject.path().substring("/rpm/".length()));
            }
            return;
        }
        if (subject.coordinate() != null && subject.version() != null) {
            for (String key : format.blobKeys(subject.coordinate(), subject.version(), store)) {
                refresh(listings, key.substring("rpm/".length()));
            }
            return;
        }
        if (blobs.isEmpty("rpm")) {
            return;
        }
        LOGGER.info("RPM metadata regenerated in place: a hold transition named only a content hash");
        StoredListing.rebuildUnder(store, "rpm/", this);
    }

    /** Re-decide the stanza of {@code <repo>/<location>}. */
    private static void refresh(RpmListings listings, String rest) throws IOException {
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return;
        }
        listings.refresh(rest.substring(0, slash), rest.substring(slash + 1));
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return format.listings(new Blobs(store)).rebuild(listing);
    }
}
