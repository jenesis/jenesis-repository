package build.jenesis.repository.format.conan;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.Keys;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ListingObserver;
import build.jenesis.repository.store.StoredListing;

/**
 * Keeps the Conan {@linkplain ConanListings stored revision index} in step with the transitions that happen off the
 * upload path - a hold on a stored file and its release, a removal - by re-deciding the one file's entry and its
 * revision's. A transition that names a coordinate and version but no file regenerates that version's documents in
 * place, across every registry; one that names only a content hash regenerates every Conan document. A lifecycle
 * mark changes nothing a Conan client reads.
 */
public final class ConanListingObserver implements ListingObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConanListingObserver.class);

    public ConanListingObserver() {
    }

    @Override
    public void onMarked(ArtifactDescriptor subject, ArtifactStore store) {
        // A lifecycle mark changes nothing a Conan client reads.
    }

    @Override
    public void transition(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        if (subject.ecosystem() != null && !subject.ecosystem().equals(ConanFormat.ECOSYSTEM)) {
            return;
        }
        Blobs blobs = new Blobs(store);
        ConanFormat.FileRef file = subject.path() == null ? null : ConanFormat.locate(subject.path());
        if (file != null) {
            if (!store.isEmpty(file.parent()) || StoredListing.present(store,
                    ConanListings.revisions(file.parent()))) {
                new ConanListings(blobs).refresh(file.parent(), file.revision(), file.filename());
            }
            return;
        }
        if (subject.coordinate() != null && subject.version() != null) {
            if (Keys.unsafe(subject.coordinate()) || Keys.unsafe(subject.version())) {
                return;
            }
            for (String repo : store.list("conan")) {
                StoredListing.rebuildUnder(store,
                        "conan/" + repo + "/r/" + subject.coordinate() + "/" + subject.version() + "/", this);
            }
            return;
        }
        if (subject.path() != null || store.isEmpty("conan")) {
            return;
        }
        LOGGER.info("Conan revision index regenerated in place: a hold transition named only a content hash");
        StoredListing.rebuildUnder(store, "conan/", this);
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return new ConanListings(new Blobs(store)).rebuild(listing);
    }
}
