package build.jenesis.repository.format.npm;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ListingObserver;
import build.jenesis.repository.store.StoredListing;

/**
 * Keeps the npm {@linkplain NpmListings stored packuments} in step with the transitions that happen off the publish
 * path - a hold on a published version and its release, a lifecycle mark and its reversal, a removal - by
 * re-deciding the one version's entry. A transition whose subject names neither a tarball path nor a coordinate is
 * mapped to no entry, so every packument is rebuilt in place.
 */
public final class NpmListingObserver implements ListingObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger(NpmListingObserver.class);

    private final NpmFormat format = new NpmFormat();

    public NpmListingObserver() {
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
            if (blobs.exists("npm/" + named.coordinate() + "/versions/" + named.version())
                    || StoredListing.present(store, NpmListings.packument(named.coordinate()))) {
                new NpmListings(blobs).refresh(named.coordinate(), named.version());
            }
            return;
        }
        if (named.path() != null || blobs.isEmpty("npm")) {
            return;
        }
        LOGGER.info("npm packuments regenerated in place: a hold transition named only a content hash");
        StoredListing.rebuildUnder(store, "npm/", this);
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return new NpmListings(new Blobs(store)).rebuild(listing);
    }
}
