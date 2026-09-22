package build.jenesis.repository.format.go;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ListingObserver;
import build.jenesis.repository.store.StoredListing;

/**
 * Keeps the Go {@linkplain GoListings stored version lists} in step with the transitions that happen off the publish
 * path - a hold on a published version and its release, a yank and its reversal, a removal - by re-deciding the one
 * version's membership. A transition whose subject names neither a module path nor a coordinate is mapped to no
 * version, so every list is rebuilt in place.
 */
public final class GoListingObserver implements ListingObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger(GoListingObserver.class);

    private final GoFormat format = new GoFormat();

    public GoListingObserver() {
    }

    @Override
    public void transition(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        if (subject.ecosystem() != null && !subject.ecosystem().equals(format.ecosystem())) {
            return;
        }
        Blobs blobs = new Blobs(store);
        ArtifactDescriptor named = subject;
        if (named.coordinate() == null && named.path() != null && named.path().startsWith("/go/")) {
            // /go/<module>/@v/<version>.<ext>
            int at = named.path().indexOf("/@v/");
            if (at > 0) {
                String file = named.path().substring(at + "/@v/".length());
                int dot = file.lastIndexOf('.');
                if (dot > 0) {
                    named = new ArtifactDescriptor(format.ecosystem(), named.path().substring("/go/".length(), at),
                            file.substring(0, dot), named.path(), null, false, named.hash(), -1L);
                }
            }
        }
        if (named.coordinate() != null && named.version() != null) {
            if (blobs.exists("go/" + named.coordinate() + "/@v/" + named.version() + ".info")
                    || StoredListing.present(store, GoListings.list(named.coordinate()))) {
                new GoListings(blobs).refresh(named.coordinate(), named.version());
            }
            return;
        }
        if (named.path() != null || blobs.isEmpty("go")) {
            return;
        }
        LOGGER.info("Go version lists regenerated in place: a hold transition named only a content hash");
        StoredListing.rebuildUnder(store, "go/", this);
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return new GoListings(new Blobs(store)).rebuild(listing);
    }
}
