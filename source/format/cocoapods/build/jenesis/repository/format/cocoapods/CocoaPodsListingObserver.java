package build.jenesis.repository.format.cocoapods;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ListingObserver;
import build.jenesis.repository.store.StoredListing;

/**
 * Keeps the CocoaPods {@linkplain CocoaPodsListings stored shard listings} in step with the transitions that happen
 * off the publish path - a hold on a published version and its release, a yank and its reversal, a removal - by
 * re-deciding the one version's membership. A transition whose subject names neither a pod path nor a coordinate is
 * mapped to no version, so every listing is regenerated in place.
 */
public final class CocoaPodsListingObserver implements ListingObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger(CocoaPodsListingObserver.class);

    private final CocoaPodsFormat format = new CocoaPodsFormat();

    public CocoaPodsListingObserver() {
    }

    @Override
    public void transition(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        if (subject.ecosystem() != null && !subject.ecosystem().equals(format.ecosystem())) {
            return;
        }
        Blobs blobs = new Blobs(store);
        ArtifactDescriptor named = subject;
        if (named.coordinate() == null && named.path() != null) {
            named = format.describe(named.path()).orElse(named);
        }
        if (named.coordinate() != null && named.version() != null) {
            for (String repo : blobs.list("cocoapods")) {
                if (blobs.exists(CocoaPodsFormat.blobKey(repo, named.coordinate(), named.version()))) {
                    new CocoaPodsListings(blobs).refresh(repo, named.coordinate(), named.version());
                }
            }
            return;
        }
        if (named.path() != null || blobs.isEmpty("cocoapods")) {
            return;
        }
        LOGGER.info("CocoaPods shard listings regenerated in place: a hold transition named only a content hash");
        StoredListing.rebuildUnder(store, "cocoapods/", this);
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return new CocoaPodsListings(new Blobs(store)).rebuild(listing);
    }
}
