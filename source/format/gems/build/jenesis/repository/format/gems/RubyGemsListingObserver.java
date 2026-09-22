package build.jenesis.repository.format.gems;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ListingObserver;
import build.jenesis.repository.store.StoredListing;

/**
 * Keeps the RubyGems {@linkplain RubyGemsListings stored compact index} in step with the transitions that happen off
 * the push path - a hold on a pushed gem and its release, a lifecycle mark and its reversal, a removal - by
 * re-deciding the one version's membership in its gem's info document (which re-derives the gem's line in
 * {@code /versions}). A transition whose subject names neither a gem path nor a coordinate is mapped to no version,
 * so every listing is rebuilt in place.
 */
public final class RubyGemsListingObserver implements ListingObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger(RubyGemsListingObserver.class);

    private final RubyGemsFormat format = new RubyGemsFormat();

    public RubyGemsListingObserver() {
    }

    @Override
    public void transition(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        if (subject.ecosystem() != null && !subject.ecosystem().equals(format.ecosystem())) {
            return;
        }
        Blobs blobs = new Blobs(store);
        RubyGemsListings listings = new RubyGemsListings(blobs);
        ArtifactDescriptor named = subject;
        if (named.coordinate() == null && named.path() != null) {
            named = format.describe(named.path()).orElse(named);
        }
        if (named.coordinate() != null && named.version() != null) {
            if (subject.ecosystem() == null
                    && blobs.isEmpty("rubygems/" + named.coordinate() + "/versions")) {
                return;   // a mark on a coordinate this repository holds no gem of
            }
            listings.refresh(named.coordinate(), named.version());
            return;
        }
        if (named.path() != null || blobs.isEmpty("rubygems")) {
            return;
        }
        LOGGER.info("RubyGems listings regenerated in place: a hold transition named only a content hash");
        StoredListing.rebuildUnder(store, "rubygems/", this);
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return new RubyGemsListings(new Blobs(store)).rebuild(listing);
    }
}
