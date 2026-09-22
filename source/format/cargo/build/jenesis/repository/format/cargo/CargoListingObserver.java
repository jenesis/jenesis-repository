package build.jenesis.repository.format.cargo;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ListingObserver;
import build.jenesis.repository.store.StoredListing;

/**
 * Keeps the Cargo {@linkplain CargoListings stored sparse index} in step with the transitions that happen off the
 * publish path - a hold on a published version and its release, a yank and its reversal, a removal - by
 * re-deciding the one version's line. A transition whose subject names neither a download path nor a coordinate is
 * mapped to no line, so every index is regenerated in place.
 */
public final class CargoListingObserver implements ListingObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger(CargoListingObserver.class);

    private final CargoFormat format = new CargoFormat();

    public CargoListingObserver() {
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
            // A lifecycle mark is keyed <repo>/<crate>; a hold names the crate alone and is looked for in every repo.
            String coordinate = named.coordinate();
            int slash = coordinate.indexOf('/');
            List<String> repos = slash > 0 ? List.of(coordinate.substring(0, slash)) : blobs.list("cargo");
            String crate = (slash > 0 ? coordinate.substring(slash + 1) : coordinate).toLowerCase(Locale.ROOT);
            for (String repo : repos) {
                if (blobs.exists(CargoFormat.indexKey(repo, crate, named.version()))) {
                    new CargoListings(blobs).refresh(repo, crate, named.version());
                }
            }
            return;
        }
        if (named.path() != null || blobs.isEmpty("cargo")) {
            return;
        }
        LOGGER.info("Cargo sparse indexes regenerated in place: a hold transition named only a content hash");
        StoredListing.rebuildUnder(store, "cargo/", this);
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return new CargoListings(new Blobs(store)).rebuild(listing);
    }
}
