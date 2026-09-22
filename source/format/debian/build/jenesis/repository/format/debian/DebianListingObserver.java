package build.jenesis.repository.format.debian;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ListingObserver;
import build.jenesis.repository.store.StoredListing;

/**
 * Keeps the Debian {@linkplain DebianListings stored listings} in step with the transitions that happen off the push
 * path: a hold placed on a published package (and its release), a yank or its reversal, and a removal. Each re-decides
 * the one stanza's membership in its {@code Packages} document - the write-path counterpart of the per-stanza screen
 * the on-read generation used to apply. A transition whose subject names neither a pool path nor a Debian coordinate
 * cannot be mapped to a stanza, so the repository's listings are regenerated in place.
 */
public final class DebianListingObserver implements ListingObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger(DebianListingObserver.class);

    private final DebianFormat format = new DebianFormat();

    public DebianListingObserver() {
    }

    @Override
    public void transition(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        if (subject.ecosystem() != null && !subject.ecosystem().equals(format.ecosystem())) {
            return;
        }
        Blobs blobs = new Blobs(store);
        DebianListings listings = format.listings(blobs);
        if (subject.path() != null) {
            if (!subject.path().startsWith("/debian/") || !subject.path().endsWith(".deb")) {
                return;
            }
            refresh(listings, subject.path().substring("/debian/".length()));
            return;
        }
        if (subject.coordinate() != null && subject.version() != null) {
            List<String> keys = format.blobKeys(subject.coordinate(), subject.version(), store);
            if (subject.ecosystem() == null && keys.isEmpty()) {
                return;   // a mark on a coordinate this repository holds no Debian package of
            }
            for (String key : keys) {
                refresh(listings, key.substring("debian/".length()));
            }
            return;
        }
        if (blobs.isEmpty("debian")) {
            return;
        }
        LOGGER.info("Debian listings regenerated in place: a hold transition named only a content hash");
        StoredListing.rebuildUnder(store, "debian/", this);
    }

    /** Re-decide the stanza of the pool path {@code <suite>/pool/<component>/.../<file>} in every index that carries it. */
    private static void refresh(DebianListings listings, String poolRest) throws IOException {
        String[] segments = poolRest.split("/");
        if (segments.length < 4 || !segments[1].equals("pool")) {
            return;
        }
        String suite = segments[0], component = segments[2], file = segments[segments.length - 1];
        for (String[] index : listings.indexesOf(suite, component, file)) {
            listings.refresh(suite, index[0], index[1], file);
        }
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return format.listings(new Blobs(store)).rebuild(listing);
    }
}
