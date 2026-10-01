package build.jenesis.repository.staging.store;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublishInterceptor;

/**
 * Withholds the staging subtree from every serving read, as a quarantine pointer is. {@link StoreStaging#stage} links
 * each staged blob at a {@code publish/staging/<id><releasePath>} pointer so the lifecycle can hold, list and
 * re-publish it - but that is otherwise a live serving pointer, and for a format with no mount in front of the path
 * (Maven, say) a {@code GET /repository/<tenant>/<repo>/staging/<id>/...} would resolve through
 * {@link build.jenesis.repository.store.Publication#located} and serve bytes no gate has seen. This
 * {@link PublishInterceptor} never changes a verdict; it reports every {@code /staging/} request path as withheld until
 * promotion re-publishes the content at its real release path.
 *
 * <p>Promotion, listing, reaping and cleanup read the staged pointers through
 * {@link build.jenesis.repository.store.Publication#blob} and {@link ArtifactStore#list}, never {@code located}, so
 * they are unaffected.
 */
public final class StagingWithholdInterceptor implements PublishInterceptor {

    /** The request-path prefix staged pointers live under - the one subtree never served. */
    private static final String STAGING = "/staging/";

    @Override
    public boolean withheld(String path, ArtifactStore store) {
        return path.startsWith(STAGING);
    }
}
