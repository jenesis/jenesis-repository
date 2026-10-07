package build.jenesis.repository.store;

import module java.base;

/**
 * The recipe every format's stored-listing observer follows.
 *
 * <p>A {@linkplain StoredListing stored listing} is maintained on the write path, so every transition that changes
 * what it shows has to reach it: a removal, a hold and its release, a lifecycle mark and its reversal. A publish is
 * the exception - it writes its own entry as part of laying the artifact out, so there is nothing to do after the
 * fact. That leaves one question a format actually answers, {@linkplain #transition which entry does this
 * transition change}, and four callbacks that ask it.
 *
 * <p><b>Why this is an interface rather than a convention.</b> When {@link PublicationObserver} grows a transition,
 * a callback written out per format would default to a no-op on every one of them, and every listing would silently
 * go stale for that transition. Declared here, a transition added to the seam is a transition every format handles.
 *
 * <p><b>Opting out is deliberate and written down.</b> A format whose listings do not mirror a lifecycle flag
 * overrides {@link #onMarked} with an empty body <em>and the reason</em> - the OCI, raw, Conan and Hugging Face
 * observers each carry one, of the shape "a lifecycle mark changes nothing a Conan client reads". An empty
 * override with no reason is indistinguishable from a forgotten one.
 *
 * <h2>Contract</h2>
 * <p>{@link PublicationObserver}'s contract holds; this role adds:
 * <ol>
 *   <li><b>Idempotency / replay.</b> {@link #transition} re-decides its entry from what the store says now - held,
 *       marked, removed - rather than applying the transition as a change, so a repeated or reordered transition
 *       converges on the same listing.</li>
 *   <li><b>Error visibility.</b> Contained, as every observer leg is: a transition that fails leaves the listing
 *       stale until the rebuild walk regenerates it through this format's {@link StoredListing.Rebuilder}, and never
 *       fails the transition that reached it.</li>
 *   <li><b>Tenant scoping.</b> The store handed in is the repository's, and a subject of another format's ecosystem
 *       is not this observer's to act on.</li>
 *   <li><b>Bounded work.</b> A subject naming a coordinate or a path changes that one entry; only a subject naming
 *       nothing but a content hash regenerates the format's documents, in place ({@link StoredListing#rebuildUnder}),
 *       and never by deleting them under a reader.</li>
 * </ol>
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
