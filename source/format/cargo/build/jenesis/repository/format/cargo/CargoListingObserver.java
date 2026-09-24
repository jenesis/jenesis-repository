package build.jenesis.repository.format.cargo;

import module java.base;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.BlobsListingObserver;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Keeps the Cargo {@linkplain CargoListings stored sparse index} in step with the transitions that happen off the
 * publish path - a hold on a published version and its release, a yank and its reversal, a removal - by
 * re-deciding the one version's line. A transition whose subject names neither a download path nor a coordinate is
 * mapped to no line, so every index is regenerated in place.
 */
public final class CargoListingObserver extends BlobsListingObserver {

    public CargoListingObserver() {
        super(new CargoFormat(), "cargo", "Cargo sparse indexes");
    }

    @Override
    protected void refresh(Blobs blobs, ArtifactStore store, ArtifactDescriptor subject, ArtifactDescriptor named)
            throws IOException {
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
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return new CargoListings(new Blobs(store)).rebuild(listing);
    }
}
