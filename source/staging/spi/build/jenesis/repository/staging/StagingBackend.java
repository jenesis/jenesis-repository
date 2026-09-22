package build.jenesis.repository.staging;

import module java.base;

/**
 * The storage side of staging, kept behind a seam so the lifecycle is testable without a store. {@link #promote}
 * publishes one staged item into the release layout (pointing the release path at the already-stored blob);
 * {@link #discard} removes everything staged under an id, garbage-collecting any blob no surviving pointer still
 * references. Production backs this with the artifact store; a test backs it with a recorder.
 */
public interface StagingBackend {

    void promote(String stagingId, StagedItem item) throws IOException;

    void discard(String stagingId) throws IOException;
}
