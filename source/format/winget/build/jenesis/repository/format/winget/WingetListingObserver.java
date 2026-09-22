package build.jenesis.repository.format.winget;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ListingObserver;
import build.jenesis.repository.store.StoredListing;

/**
 * Keeps the winget {@linkplain WingetListings stored documents} in step with the transitions that happen off the
 * publish path - a hold on a published version and its release, a lifecycle mark and its reversal, a removal - by
 * re-deciding the one version's membership of its package list, which re-derives that package's line in the
 * repository index. A transition that names only a content hash maps to no version, so the documents are regenerated
 * in place rather than left describing a package the read will refuse to serve.
 */
public final class WingetListingObserver implements ListingObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger(WingetListingObserver.class);

    private final WingetFormat format = new WingetFormat();

    public WingetListingObserver() {
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
            for (String repo : blobs.list("winget")) {
                if (blobs.exists(WingetFormat.manifestKey(repo, named.coordinate(), named.version()))) {
                    new WingetListings(blobs).refresh(repo, named.coordinate(), named.version());
                }
            }
            return;
        }
        if (named.path() != null || blobs.isEmpty("winget")) {
            return;
        }
        LOGGER.info("winget listings regenerated in place: a hold transition named only a content hash");
        StoredListing.rebuildUnder(store, "winget/", this);
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return new WingetListings(new Blobs(store)).rebuild(listing);
    }
}
