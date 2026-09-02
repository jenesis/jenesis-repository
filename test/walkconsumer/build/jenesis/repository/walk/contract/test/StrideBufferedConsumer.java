package build.jenesis.repository.walk.contract.test;

import module java.base;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.WalkConsumer;

/**
 * The <b>stride durable</b> archetype: deliveries are batched in memory and flushed from
 * {@link WalkConsumer#beforeCheckpoint}, so one store round trip covers a whole checkpoint stride instead of one per
 * artifact. Over a store with millions of artifacts that difference is the whole cost of a rebuild, so this is the
 * shape a real index rebuilder wants - and it is safe for exactly one reason: the flush is ordered <em>before</em> the
 * cursor commit that would otherwise skip the batch.
 *
 * <p>Remove that ordering - or the hook - and this consumer becomes silently lossy at precisely one crash point: the
 * cursor lands, the process dies, and the buffered rows are never replayed because the walk believes they were
 * processed. {@code CRASH_AFTER_THE_CHECKPOINT_LANDED_CONVERGES} is that point, and this fixture is what makes it a
 * real assertion rather than a comment.
 *
 * <p>The store is captured from {@code onRetained}: the pass hooks carry only the {@code WalkPass}, and
 * {@code beforeCheckpoint} is suppressed until this worker has been handed something, so a flush always has one.
 */
public final class StrideBufferedConsumer implements WalkConsumer {

    /** The consumer name, its toggle key and its settings namespace. */
    public static final String NAME = "walkkit-buffered";

    /** Where its rows live - the same one-object-per-pointer shape as its streaming peer, written a stride at a time. */
    public static final String SPACE = "derived/buffered";

    private final Map<String, String> buffered = new LinkedHashMap<>();
    private ArtifactStore store;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void onRetained(ArtifactDescriptor artifact, ArtifactStore store) {
        this.store = store;
        buffered.put(artifact.path(), artifact.hash());
    }

    @Override
    public void beforeCheckpoint(String cursor) throws IOException {
        if (store == null) {
            return;                       // nothing was delivered on this worker, so there is nothing to flush
        }
        for (Map.Entry<String, String> row : buffered.entrySet()) {
            KitCorpus.write(store, SPACE + "/" + KitCorpus.encode(row.getKey()), row.getValue());
        }
        // Cleared only once every row landed: a flush that throws leaves the batch in hand AND leaves the previous
        // cursor standing, so the re-visit replays exactly what was lost.
        buffered.clear();
    }
}
