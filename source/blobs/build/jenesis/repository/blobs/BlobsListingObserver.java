package build.jenesis.repository.blobs;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ListingObserver;
import build.jenesis.repository.store.StoredListing;

/**
 * How a blobs-namespace format keeps its stored listings in step with the transitions that happen off the publish
 * path - a hold and its release, a lifecycle mark and its reversal, a removal. A transition for another ecosystem is
 * ignored; one that names a coordinate, directly or through the path its format describes, re-decides that entry
 * ({@link #refresh}); and one that names only a content hash, so no entry can be told apart, regenerates every
 * listing under the format's namespace in place, never deleting one under a reader.
 *
 * <p>The format supplies what is its own: which descriptor names an entry, how one entry is refreshed, and how a
 * whole document is rebuilt. The routing around them was written the same way in each format's observer, and
 * lives here once.
 */
public abstract class BlobsListingObserver implements ListingObserver {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final BlobLayout format;
    private final String namespace;
    private final String documents;

    /**
     * An observer for {@code format}, whose listings live under {@code namespace} in the blobs space;
     * {@code documents} names them in the log line a whole-namespace regeneration writes.
     */
    protected BlobsListingObserver(BlobLayout format, String namespace, String documents) {
        this.format = format;
        this.namespace = namespace;
        this.documents = documents;
    }

    @Override
    public final void transition(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        if (subject.ecosystem() != null && !subject.ecosystem().equals(format.ecosystem())) {
            return;
        }
        Blobs blobs = new Blobs(store);
        ArtifactDescriptor named = subject;
        if (named.coordinate() == null && named.path() != null) {
            named = format.describe(named.path()).orElse(named);
        }
        if (names(named)) {
            refresh(blobs, store, subject, named);
            return;
        }
        if (named.path() != null || blobs.isEmpty(namespace)) {
            return;
        }
        logger.info("{} regenerated in place: a hold transition named only a content hash", documents);
        StoredListing.rebuildUnder(store, namespace + "/", this);
    }

    /** Whether {@code named} identifies one entry this format's listings hold: a coordinate and a version, unless a
     *  format lists by coordinate alone. */
    protected boolean names(ArtifactDescriptor named) {
        return named.coordinate() != null && named.version() != null;
    }

    /**
     * Re-decide the one entry {@code named} identifies, in whichever listings hold it. {@code subject} is the
     * transition as it arrived, before its path was described.
     */
    protected abstract void refresh(Blobs blobs, ArtifactStore store, ArtifactDescriptor subject,
                                    ArtifactDescriptor named) throws IOException;
}
