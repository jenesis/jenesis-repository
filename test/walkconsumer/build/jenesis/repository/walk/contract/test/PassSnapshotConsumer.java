package build.jenesis.repository.walk.contract.test;

import module java.base;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.WalkConsumer;
import build.jenesis.repository.walk.WalkPass;

/**
 * The <b>pass snapshot</b> archetype: the whole view is accumulated in memory and committed as <em>one</em> object
 * from {@code onPassCompleted} - the shape the SPI's javadoc names ("a snapshot rebuilder (one artifact committed at
 * pass end) restarts its own accumulation after a crash and says so"). It is the only one of the three that a
 * crash-resume does <em>not</em> converge, and the interesting one for that reason.
 *
 * <p><b>Why it cannot converge, and what it must do instead.</b> A resumed pass replays only the tail past the last
 * committed cursor, so what reaches {@code onPassCompleted} after a crash is a fragment of the store. Committing it
 * would replace a whole view with a partial one and serve it as if it were whole - the one outcome &sect;5 forbids.
 * The signal that this is happening is the pass <b>generation</b>: a fresh pass is a new generation, whereas a resume
 * hands this consumer the same generation it already began accumulating for. Its own memory did not survive the crash,
 * so the generation it began has to be <em>durable</em> - the {@code pending} marker below. Seeing it again, the
 * consumer refuses to commit, records a degradation reason instead, and leaves whatever complete snapshot it had. The
 * next full pass (a new generation) rebuilds and clears both markers.
 *
 * <p>Two SPI facts shape the implementation, and both are worth knowing before writing a real consumer of this class:
 * the pass hooks carry no {@link ArtifactStore}, so the store has to be captured from {@code onRetained}; and they
 * cannot throw a checked {@link IOException}, so a failed commit is wrapped in an {@link UncheckedIOException}, which
 * propagates out of the pass in the same way a checked one would.
 */
public final class PassSnapshotConsumer implements WalkConsumer {

    /** The consumer name, its toggle key and its settings namespace. */
    public static final String NAME = "walkkit-snapshot";

    /** Its key space: one committed snapshot, one resume marker, one degradation say-so. */
    public static final String SPACE = "derived/snapshot";

    /** The committed view - the only thing a reader of this consumer ever sees. */
    public static final String SNAPSHOT = SPACE + "/index";

    /** The generation whose accumulation is in flight; present means "a pass began and never committed". */
    public static final String PENDING = SPACE + "/pending";

    /** Why the last pass could not converge - the &sect;5 say-so, so an empty or stale view is never ambiguous. */
    public static final String DEGRADED = SPACE + "/degraded";

    private final Map<String, String> accumulated = new TreeMap<>();
    private ArtifactStore store;
    private long generation = -1;
    private boolean checked;
    private boolean resumed;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void onPassStarted(WalkPass pass) {
        // The reset the javadoc names: a snapshot rebuilder starts its accumulation over at every pass.
        accumulated.clear();
        generation = pass.generation();
        checked = false;
        resumed = false;
    }

    @Override
    public void onRetained(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        this.store = store;
        if (!checked) {
            checked = true;
            // Durable, because in-memory state did not survive the process that began this generation.
            resumed = Long.toString(generation).equals(KitCorpus.text(store, PENDING));
            if (!resumed) {
                KitCorpus.write(store, PENDING, Long.toString(generation));
            }
        }
        accumulated.put(artifact.path(), artifact.hash());
    }

    @Override
    public void onPassCompleted(WalkPass pass) {
        if (store == null) {
            return;               // this worker was handed nothing, so it has neither a view nor a store to write with
        }
        try {
            if (resumed) {
                // Degrade and SAY so. The previous snapshot - complete, possibly stale - stands untouched.
                KitCorpus.write(store, DEGRADED, "generation " + pass.generation() + " was resumed after a crash, so "
                        + "this pass saw only the tail past the last committed cursor; the snapshot was not replaced "
                        + "and rebuilds on the next full pass");
                return;
            }
            StringBuilder snapshot = new StringBuilder();
            accumulated.forEach((path, hash) -> snapshot.append(path).append('\t').append(hash).append('\n'));
            KitCorpus.write(store, SNAPSHOT, snapshot.toString());
            store.delete(PENDING);
            store.delete(DEGRADED);
        } catch (IOException failure) {
            // The hooks cannot declare a checked exception; an unchecked one propagates out of the pass identically,
            // and losing this write silently would be exactly the swallowed failure §9 forbids.
            throw new UncheckedIOException(failure);
        }
    }
}
