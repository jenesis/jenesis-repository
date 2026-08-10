package build.jenesis.repository.publication.contract.test;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;

import module java.base;

/**
 * The durable-after-enqueue archetype: the callback writes a durable note and a later drain performs the effect - the
 * shape a forwarding, webhook or replication observer takes, so a remote target's latency or outage never couples
 * into the local publish.
 *
 * <p>Its two key spaces are deliberately distinct, because they are what tells its delivery class from best-effort's:
 * {@code pending/} is durable the moment the callback returns and {@code sent/} is the delivered surface. A crash
 * <em>after</em> the callback returned therefore loses nothing, which best-effort cannot say. A crash <em>before</em>
 * it - the commit-to-callback window - loses the note entirely, which is why this class is still not at-least-once
 * and still needs the sweep.
 */
public final class OutboxObserver implements PublicationObserver {

    /** The key space this surface owns. */
    public static final String SPACE = "kitoutbox";

    /** Notes durably recorded on the publish thread, not yet delivered. */
    public static final String PENDING = SPACE + "/pending";

    /** The delivered surface - what the drain produced. */
    public static final String SENT = SPACE + "/sent";

    @Override
    public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        // The whole callback: one small durable note, no remote call. Anything slow belongs to the drain.
        Keys.upsert(store, PENDING + "/" + Keys.slug(artifact.path()), IndexObserver.row(artifact));
    }

    @Override
    public void onDeleted(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        store.delete(PENDING + "/" + Keys.slug(artifact.path()));
        store.delete(SENT + "/" + Keys.slug(artifact.path()));
    }

    /** Deliver every pending note. Idempotent by construction: the delivery is an upsert keyed by the same row the
     *  note is, so a drain that crashed after writing and before clearing simply re-writes the same row. */
    static void drain(ArtifactStore store) throws IOException {
        for (Map.Entry<String, String> note : Keys.rows(store, PENDING).entrySet()) {
            Keys.upsert(store, SENT + "/" + note.getKey(), note.getValue());
            store.delete(PENDING + "/" + note.getKey());
        }
    }
}
