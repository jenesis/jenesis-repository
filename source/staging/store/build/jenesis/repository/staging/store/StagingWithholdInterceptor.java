package build.jenesis.repository.staging.store;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublishInterceptor;

/**
 * Withholds the staging subtree from every serving read, so staged-but-not-yet-promoted content is held out of the
 * release layout exactly as a quarantine pointer is. {@link StoreStaging#stage} links each staged blob at a
 * {@code publish/staging/&lt;id&gt;&lt;releasePath&gt;} pointer so the lifecycle can hold, list and later re-publish it,
 * but that pointer is otherwise a live serving pointer: without this screen a {@code GET
 * /repository/&lt;tenant&gt;/&lt;repo&gt;/staging/&lt;id&gt;/...} of a repository whose format puts no mount in front
 * of the path - a Maven one, say - resolves through {@link build.jenesis.repository.store.Publication#located}
 * and serves the un-gated staged bytes before any promotion ran the compliance gate. This is the quarantine read side
 * (a discovered {@link PublishInterceptor} whose only say is {@link #withheld}): it never changes a verdict, it simply
 * reports every {@code /staging/} request path as withheld, so staged content is invisible to serving until promotion
 * re-publishes it into its real release path (which does not sit under {@code /staging/} and is served normally).
 *
 * <p>Promotion, listing, reaping and the residual-cleanup all read the staged pointers through
 * {@link build.jenesis.repository.store.Publication#blob} / {@link ArtifactStore#list} directly (never through
 * {@code located}), so withholding the read side leaves the lifecycle's own operations untouched.
 */
public final class StagingWithholdInterceptor implements PublishInterceptor {

    /** The request-path prefix the staging lifecycle links its held pointers under - the one subtree never served. */
    private static final String STAGING = "/staging/";

    @Override
    public boolean withheld(String path, ArtifactStore store) {
        return path.startsWith(STAGING);
    }
}
