package build.jenesis.repository.staging;

import module java.base;

/**
 * The storage side of staging, behind a seam so the lifecycle is testable without a store. {@link #promote} points one
 * item's release path at its already-stored blob; {@link #discard} removes everything staged under an id, leaving
 * unreferenced blobs to collection.
 */
public interface StagingBackend {

    void promote(String stagingId, StagedItem item) throws IOException;

    void discard(String stagingId) throws IOException;
}
