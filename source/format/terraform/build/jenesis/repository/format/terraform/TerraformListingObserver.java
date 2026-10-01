package build.jenesis.repository.format.terraform;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ListingObserver;
import build.jenesis.repository.store.StoredListing;

/**
 * Keeps the {@linkplain TerraformListings stored version documents} in step with transitions off the publish path - a
 * hold and its release, a yank and its reversal, a removal. A provider transition re-decides two documents: the
 * release's {@code SHA256SUMS}, so a held platform's digest stops being declared, and the version's entry, since a
 * version whose every platform is held is no longer offered and an empty checksums file would read as a corrupt
 * registry.
 */
public final class TerraformListingObserver implements ListingObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger(TerraformListingObserver.class);

    private final TerraformFormat format = new TerraformFormat();

    public TerraformListingObserver() {
    }

    @Override
    public void transition(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        if (subject.ecosystem() != null && !subject.ecosystem().equals(format.ecosystem())) {
            return;
        }
        Blobs blobs = new Blobs(store);
        if (subject.path() != null) {
            if (subject.path().startsWith("/terraform/")) {
                refresh(blobs, subject.path().substring("/terraform/".length()));
            }
            return;
        }
        if (subject.coordinate() != null && subject.version() != null) {
            for (String key : format.blobKeys(subject.coordinate(), subject.version(), store)) {
                refresh(blobs, key.substring("terraform/".length()));
            }
            return;
        }
        if (blobs.isEmpty("terraform")) {
            return;
        }
        LOGGER.info("terraform version documents regenerated in place: a hold transition named only a content hash");
        StoredListing.rebuildUnder(store, "terraform/", this);
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return new TerraformFormat().listings(new Blobs(store)).rebuild(listing);
    }

    /** Re-decide the documents that describe {@code <repo>/<kind>/...}. */
    private void refresh(Blobs blobs, String rest) throws IOException {
        String[] path = rest.split("/");
        TerraformListings listings = format.listings(blobs);
        if (path.length == 6 && path[1].equals("modules") && path[5].endsWith(".tar.gz")) {
            listings.moduleRefresh(path[0], path[2], path[3], path[4],
                    path[5].substring(0, path[5].length() - ".tar.gz".length()));
        } else if (path.length == 6 && path[1].equals("providers") && path[5].endsWith(".zip")) {
            listings.providerRefresh(path[0], path[2], path[3], path[4], path[5]);
        }
    }
}
