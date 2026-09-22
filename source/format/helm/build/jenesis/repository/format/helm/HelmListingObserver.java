package build.jenesis.repository.format.helm;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ListingObserver;
import build.jenesis.repository.store.StoredListing;

/**
 * Keeps {@code index.yaml} in step with the transitions that happen off the publish path - a hold on a published
 * chart and its release, a deprecation mark and its reversal, a removal - by re-deciding the affected chart's whole
 * block. The block is the unit because that is how the index is keyed; re-deciding it costs the chart's own versions
 * and never the repository's other charts.
 */
public final class HelmListingObserver implements ListingObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger(HelmListingObserver.class);

    private final HelmFormat format = new HelmFormat();

    public HelmListingObserver() {
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
        if (named.coordinate() != null) {
            for (String repo : blobs.list("helm")) {
                if (!blobs.isEmpty(HelmFormat.entryPrefix(repo) + "/" + named.coordinate())) {
                    new HelmListings(blobs).refresh(repo, named.coordinate());
                }
            }
            return;
        }
        if (named.path() != null || blobs.isEmpty("helm")) {
            return;
        }
        LOGGER.info("Helm index regenerated in place: a hold transition named only a content hash");
        StoredListing.rebuildUnder(store, "helm/", this);
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return new HelmListings(new Blobs(store)).rebuild(listing);
    }
}
