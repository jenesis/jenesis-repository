package build.jenesis.repository.format.huggingface;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ListingObserver;
import build.jenesis.repository.store.StoredListing;

/**
 * Keeps the Hugging Face {@linkplain HuggingFaceListings stored revision lists} in step with the transitions that
 * happen off the upload path - a hold on an uploaded file and its release, a removal - by re-deciding the one file's
 * entry. A transition that names a file path is mapped exactly; one that names only a repository and revision
 * re-decides every stored file of that revision; one that names neither rebuilds every list in place.
 */
public final class HuggingFaceListingObserver implements ListingObserver {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(HuggingFaceListingObserver.class);

    private final HuggingFaceFormat format = new HuggingFaceFormat();

    public HuggingFaceListingObserver() {
    }

    @Override
    public void onMarked(ArtifactDescriptor subject, ArtifactStore store) {
        // This format does not carry the lifecycle module: a mark changes nothing the Hub API answers.
    }

    @Override
    public void transition(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        if (subject.ecosystem() != null && !subject.ecosystem().equals(format.ecosystem())) {
            return;
        }
        Blobs blobs = new Blobs(store);
        HuggingFaceListings listings = new HuggingFaceListings(blobs);
        if (subject.path() != null) {
            HuggingFaceFormat.Located located = format.locate(subject.path());
            if (located != null) {
                listings.refresh(located.base(), located.revision(), located.type(), located.repoId(),
                        located.filepath());
            }
            return;
        }
        if (subject.coordinate() != null && subject.version() != null) {
            for (String key : format.blobKeys(subject.coordinate(), subject.version(), store)) {
                HuggingFaceFormat.Located located = format.locateKey(key);
                if (located != null) {
                    listings.refresh(located.base(), located.revision(), located.type(), located.repoId(),
                            located.filepath());
                }
            }
            return;
        }
        if (blobs.isEmpty("hf")) {
            return;
        }
        LOGGER.info("Hugging Face revision lists regenerated in place: a hold transition named only a content hash");
        StoredListing.rebuildUnder(store, "hf/", this);
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return new HuggingFaceListings(new Blobs(store)).rebuild(listing);
    }
}
