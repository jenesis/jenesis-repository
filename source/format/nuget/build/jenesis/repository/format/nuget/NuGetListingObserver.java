package build.jenesis.repository.format.nuget;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ListingObserver;
import build.jenesis.repository.store.StoredListing;

/**
 * Keeps the NuGet {@linkplain NuGetListings stored documents} in step with the transitions that happen off the push
 * path - a hold on a pushed version and its release, a lifecycle mark and its reversal, a removal - by re-deciding
 * the one version's entries. A transition whose subject names neither a package path nor a coordinate is mapped to
 * no entry, so every document is rebuilt in place.
 */
public final class NuGetListingObserver implements ListingObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger(NuGetListingObserver.class);

    private final NuGetFormat format = new NuGetFormat();

    public NuGetListingObserver() {
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
            String id = named.coordinate().toLowerCase(Locale.ROOT);
            if (!blobs.isEmpty("nuget/" + id + "/" + named.version())
                    || StoredListing.present(store, NuGetListings.versions(id))) {
                new NuGetListings(blobs).refresh(id, named.version());
            }
            return;
        }
        if (named.path() != null || blobs.isEmpty("nuget")) {
            return;
        }
        LOGGER.info("NuGet documents regenerated in place: a hold transition named only a content hash");
        StoredListing.rebuildUnder(store, "nuget/", this);
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return new NuGetListings(new Blobs(store)).rebuild(listing);
    }
}
