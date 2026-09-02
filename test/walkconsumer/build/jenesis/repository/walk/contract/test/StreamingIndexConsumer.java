package build.jenesis.repository.walk.contract.test;

import module java.base;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.WalkConsumer;

/**
 * The <b>per-item durable</b> archetype: one row written through inside {@code onRetained}, before it returns. This is
 * the shape the walk SPI's javadoc calls "a streaming consumer (a reconcile leg, a sidecar heal, a per-shard index)",
 * and the one whose crash behaviour is easiest to reason about - the cursor can only ever be <em>behind</em> the
 * derived state, so a resume replays rows that are already there and the upsert absorbs them.
 *
 * <p>The core ships no {@code WalkConsumer} of its own today (the SPI is the seam a derived-metadata plugin
 * adopts), so the contract kit's fixtures are the three archetypes the SPI documents rather than shipped consumers.
 * Each one is a realistic, minimal implementation of its delivery class - not a stub - because the kit's crash checks
 * only mean something against a consumer that really writes.
 */
public final class StreamingIndexConsumer implements WalkConsumer {

    /** The consumer name, its toggle key and its settings namespace. */
    public static final String NAME = "walkkit-streaming";

    /** Where its rows live - one object per retained pointer, keyed by the path it was handed. */
    public static final String SPACE = "derived/streaming";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void onRetained(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        // An upsert, not an append: the same artifact delivered twice (the at-least-once tail after a crash-resume)
        // must leave exactly the state one delivery leaves.
        KitCorpus.write(store, SPACE + "/" + KitCorpus.encode(artifact.path()), artifact.hash());
    }
}
