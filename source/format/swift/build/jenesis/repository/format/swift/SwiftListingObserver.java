package build.jenesis.repository.format.swift;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ListingObserver;
import build.jenesis.repository.store.StoredListing;

/**
 * Keeps the {@linkplain SwiftListings release list} in step with the transitions that happen off the publish path -
 * a hold and its release, a yank and its reversal, a removal - by re-deciding the one release's entry.
 *
 * <p>The two outcomes differ, which is why this cannot simply drop the entry: a hold removes it, and a yank
 * rewrites it as the specification's {@code problem} object. {@link SwiftListings#refresh} decides which, so this
 * only has to name the release.
 */
public final class SwiftListingObserver implements ListingObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger(SwiftListingObserver.class);

    private final SwiftFormat format = new SwiftFormat();

    public SwiftListingObserver() {
    }

    @Override
    public void transition(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        if (subject.ecosystem() != null && !subject.ecosystem().equals(format.ecosystem())) {
            return;
        }
        Blobs blobs = new Blobs(store);
        if (subject.path() != null) {
            if (subject.path().startsWith("/swift/")) {
                refresh(blobs, subject.path().substring("/swift/".length()));
            }
            return;
        }
        if (subject.coordinate() != null && subject.version() != null) {
            for (String key : format.blobKeys(subject.coordinate(), subject.version(), store)) {
                refresh(blobs, key.substring("swift/".length()));
            }
            return;
        }
        if (blobs.isEmpty("swift")) {
            return;
        }
        LOGGER.info("swift release lists regenerated in place: a hold transition named only a content hash");
        StoredListing.rebuildUnder(store, "swift/", this);
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return new SwiftListings(new Blobs(store)).rebuild(listing);
    }

    /** Re-decide the entry of {@code <repo>/<scope>/<name>/<version>.zip}. */
    private static void refresh(Blobs blobs, String rest) throws IOException {
        String[] path = rest.split("/");
        if (path.length != 4 || !path[3].endsWith(".zip")) {
            return;
        }
        new SwiftListings(blobs).refresh(path[0], path[1], path[2],
                path[3].substring(0, path[3].length() - ".zip".length()));
    }
}
