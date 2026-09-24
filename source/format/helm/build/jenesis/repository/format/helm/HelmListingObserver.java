package build.jenesis.repository.format.helm;

import module java.base;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.BlobsListingObserver;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Keeps {@code index.yaml} in step with the transitions that happen off the publish path - a hold on a published
 * chart and its release, a deprecation mark and its reversal, a removal - by re-deciding the affected chart's whole
 * block. The block is the unit because that is how the index is keyed; re-deciding it costs the chart's own versions
 * and never the repository's other charts.
 */
public final class HelmListingObserver extends BlobsListingObserver {

    public HelmListingObserver() {
        super(new HelmFormat(), "helm", "Helm index");
    }

    /** A Helm index lists a chart's every version in one entry, so a coordinate alone names one. */
    @Override
    protected boolean names(ArtifactDescriptor named) {
        return named.coordinate() != null;
    }

    @Override
    protected void refresh(Blobs blobs, ArtifactStore store, ArtifactDescriptor subject, ArtifactDescriptor named)
            throws IOException {
        for (String repo : blobs.list("helm")) {
            if (!blobs.isEmpty(HelmFormat.entryPrefix(repo) + "/" + named.coordinate())) {
                new HelmListings(blobs).refresh(repo, named.coordinate());
            }
        }
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return new HelmListings(new Blobs(store)).rebuild(listing);
    }
}
