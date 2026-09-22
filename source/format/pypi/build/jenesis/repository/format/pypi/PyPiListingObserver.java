package build.jenesis.repository.format.pypi;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ListingObserver;
import build.jenesis.repository.store.StoredListing;

/**
 * Keeps the PyPI {@linkplain PyPiListings stored Simple pages} in step with the transitions that happen off the
 * upload path - a hold on an uploaded file and its release, a yank and its reversal, a removal - by re-deciding the
 * one file's link (and its project's). A transition whose subject names neither a file path nor a coordinate is
 * mapped to no link, so every page is rebuilt in place.
 */
public final class PyPiListingObserver implements ListingObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger(PyPiListingObserver.class);

    private final PyPiFormat format = new PyPiFormat();

    public PyPiListingObserver() {
    }

    @Override
    public void transition(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        if (subject.ecosystem() != null && !subject.ecosystem().equals(format.ecosystem())) {
            return;
        }
        Blobs blobs = new Blobs(store);
        PyPiListings listings = new PyPiListings(blobs);
        if (subject.path() != null) {
            if (subject.path().startsWith("/pypi/simple/")) {
                String rest = subject.path().substring("/pypi/simple/".length());
                int slash = rest.indexOf('/');
                if (slash > 0 && rest.indexOf('/', slash + 1) < 0) {
                    listings.refresh(rest.substring(0, slash), rest.substring(slash + 1));
                }
            }
            return;
        }
        if (subject.coordinate() != null && subject.version() != null) {
            List<String> keys = format.blobKeys(subject.coordinate(), subject.version(), store);
            for (String key : keys) {
                String project = key.substring("pypi/".length(), key.indexOf("/files/"));
                listings.refresh(project, key.substring(key.lastIndexOf('/') + 1));
            }
            return;
        }
        if (blobs.isEmpty("pypi")) {
            return;
        }
        LOGGER.info("PyPI Simple pages regenerated in place: a hold transition named only a content hash");
        StoredListing.rebuildUnder(store, "pypi/", this);
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return new PyPiListings(new Blobs(store)).rebuild(listing);
    }
}
