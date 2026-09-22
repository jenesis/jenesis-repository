package build.jenesis.repository.format.composer;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ListingObserver;
import build.jenesis.repository.store.StoredListing;

/**
 * Keeps the Composer {@linkplain ComposerListings stored metadata} in step with the transitions that happen off the
 * publish path - a hold on a published version and its release, a lifecycle mark and its reversal, a removal - by
 * re-rendering the one version's entry. A transition whose subject names neither a dist path nor a coordinate is
 * mapped to no entry, so every listing is regenerated in place.
 */
public final class ComposerListingObserver implements ListingObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger(ComposerListingObserver.class);

    private final ComposerFormat format = new ComposerFormat();

    public ComposerListingObserver() {
    }

    @Override
    public void transition(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        if (subject.ecosystem() != null && !subject.ecosystem().equals(format.ecosystem())) {
            return;
        }
        Blobs blobs = new Blobs(store);
        ComposerListings listings = new ComposerListings(blobs);
        if (subject.path() != null) {
            // /composer/<repo>/dists/<vendor>/<package>/<version>.zip
            String[] segments = subject.path().split("/");
            if (segments.length == 7 && segments[1].equals("composer") && segments[3].equals("dists")
                    && segments[6].endsWith(".zip")) {
                listings.refresh(segments[2], segments[4], segments[5],
                        segments[6].substring(0, segments[6].length() - ".zip".length()));
            }
            return;
        }
        if (subject.coordinate() != null && subject.version() != null) {
            int slash = subject.coordinate().indexOf('/');
            if (slash <= 0) {
                return;
            }
            String vendor = subject.coordinate().substring(0, slash), pkg = subject.coordinate().substring(slash + 1);
            for (String repo : blobs.list("composer")) {
                if (blobs.exists(ComposerFormat.indexKey(repo, vendor, pkg, subject.version()))) {
                    listings.refresh(repo, vendor, pkg, subject.version());
                }
            }
            return;
        }
        if (blobs.isEmpty("composer")) {
            return;
        }
        LOGGER.info("Composer listings regenerated in place: a hold transition named only a content hash");
        StoredListing.rebuildUnder(store, "composer/", this);
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return new ComposerListings(new Blobs(store)).rebuild(listing);
    }
}
