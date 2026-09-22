package build.jenesis.repository.format.apk;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ListingObserver;
import build.jenesis.repository.store.StoredListing;

/**
 * Keeps the {@linkplain ApkListings stored APKINDEX} in step with the transitions that happen off the publish path -
 * a hold on a published package and its release, a yank and its reversal, a removal - by re-deciding the one
 * package's block. A transition naming neither a package path nor an Alpine coordinate regenerates this format's
 * documents in place, which is the only honest answer to a subject that is a bare content hash.
 */
public final class ApkListingObserver implements ListingObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApkListingObserver.class);

    private final ApkFormat format = new ApkFormat();

    public ApkListingObserver() {
    }

    @Override
    public void transition(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        if (subject.ecosystem() != null && !subject.ecosystem().equals(format.ecosystem())) {
            return;
        }
        Blobs blobs = new Blobs(store);
        ApkListings listings = new ApkListings(blobs);
        if (subject.path() != null) {
            if (subject.path().startsWith("/apk/")) {
                refresh(listings, subject.path().substring("/apk/".length()));
            }
            return;
        }
        if (subject.coordinate() != null && subject.version() != null) {
            for (String key : format.blobKeys(subject.coordinate(), subject.version(), store)) {
                refresh(listings, key.substring("apk/".length()));
            }
            return;
        }
        if (blobs.isEmpty("apk")) {
            return;
        }
        LOGGER.info("apk indexes regenerated in place: a hold transition named only a content hash");
        StoredListing.rebuildUnder(store, "apk/", this);
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return new ApkListings(new Blobs(store)).rebuild(listing);
    }

    /** Re-decide the block of {@code <repo>/<arch>/<file>}. */
    private static void refresh(ApkListings listings, String rest) throws IOException {
        String[] segments = rest.split("/");
        if (segments.length != 3 || !segments[2].endsWith(".apk")) {
            return;
        }
        listings.refresh(segments[0], segments[1], segments[2]);
    }
}
