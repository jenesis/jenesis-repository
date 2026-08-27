package build.jenesis.repository.store;

import module java.base;

/**
 * The recipe every format's stored-listing observer follows, stated once instead of fifteen times.
 *
 * <p>A {@linkplain StoredListing stored listing} is maintained on the write path, so every transition that changes
 * what it shows has to reach it: a removal, a hold and its release, a lifecycle mark and its reversal. A publish is
 * the exception - it writes its own entry as part of laying the artifact out, so there is nothing to do after the
 * fact. That leaves one question a format actually answers, {@linkplain #transition which entry does this
 * transition change}, and four callbacks that ask it.
 *
 * <p><b>Why this is an interface rather than a convention.</b> Every one of the fifteen format observers had
 * written those four callbacks out by hand, identically, and left {@code onPublished} empty with the same comment.
 * That is harmless until {@link PublicationObserver} grows a sixth transition - at which point the new callback
 * defaults to a no-op on all fifteen, every listing silently goes stale for that transition, and nothing fails.
 * Declared here, a transition added to the seam is a transition every format handles, because forgetting is no
 * longer something a format can do quietly.
 *
 * <p><b>Opting out is deliberate and written down.</b> A format whose listings do not mirror a lifecycle flag
 * overrides {@link #onMarked} with an empty body <em>and the reason</em> - the OCI, raw, Conan and Hugging Face
 * observers each carry one, of the shape "a lifecycle mark changes nothing a Conan client reads". An empty
 * override with no reason is indistinguishable from a forgotten one, which is the state this interface exists to
 * end.
 */
public interface ListingObserver extends PublicationObserver, StoredListing.Rebuilder {

    /**
     * Re-decide the entry this transition names.
     *
     * <p>The subject arrives in one of three shapes and a format reads whichever it can: a coordinate and version,
     * a served path it maps back to one, or neither - a bare content hash, which names no single entry and leaves
     * regenerating this format's documents in place ({@link StoredListing#rebuildUnder}) as the only honest answer.
     * A subject belonging to another format's ecosystem is not this observer's to act on.
     */
    void transition(ArtifactDescriptor subject, ArtifactStore store) throws IOException;

    /** A publish writes its own entries as it lays the artifact out; there is nothing to re-decide afterwards. */
    @Override
    default void onPublished(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
    }

    @Override
    default void onDeleted(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        transition(artifact, store);
    }

    @Override
    default void onWithheld(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        transition(subject, store);
    }

    @Override
    default void onWithholdCleared(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        transition(subject, store);
    }

    @Override
    default void onMarked(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        transition(subject, store);
    }
}
