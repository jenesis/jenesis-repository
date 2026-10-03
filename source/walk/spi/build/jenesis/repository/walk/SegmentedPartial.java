package build.jenesis.repository.walk;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A walk visitor that accumulates a partial result per segment of a pass and checkpoints it beside the walk's cursor,
 * so several workers can share one pass and any of them can take a segment over.
 *
 * <p>A key is assigned to the planned segment whose range holds it. Entering a segment drops whatever was folded in
 * memory - an uncommitted tail is redelivered to whoever walks that range next - and adopts the segment's stored
 * state when the current pass wrote it; a superseded pass's state only donates its compare-and-set token. Keys at or
 * below the adopted cursor are the crash-stride replay and are skipped, so a flush may run ahead of the walk's
 * committed cursor and never behind it.
 *
 * <p>The state is flushed in {@link #beforeCheckpoint}, before the walk commits its cursor, by a compare-and-set on the
 * token read at entry, then read back: a lost write, or a state another holder has since written, proves the segment
 * was taken over, and this worker stops rather than counting twice.
 */
public abstract class SegmentedPartial implements ArtifactWalk.KeyVisitor {

    /** This worker's identity inside the state it flushes - the fencing token's human-readable half. */
    protected final String holder = UUID.randomUUID().toString().substring(0, 8);

    private final ArtifactStore store;
    private final ArtifactWalk walk;
    private final String pass;
    private final String what;

    private List<WalkSegment> plan;
    private long generation;
    private int current = -1;
    private Object token;
    private String cursor;
    private boolean dirty;

    /**
     * @param pass the walk pass whose segment plan this follows.
     * @param what what the partial is, for the failure that names a taken-over segment.
     */
    protected SegmentedPartial(ArtifactStore store, ArtifactWalk walk, String pass, String what) {
        this.store = store;
        this.walk = walk;
        this.pass = pass;
        this.what = what;
    }

    @Override
    public final void visit(String key) throws IOException {
        if (plan == null) {
            plan = walk.segments(store, pass);
            generation = plan.isEmpty() ? 0 : plan.getFirst().generation();
        }
        int index = range(key);
        if (index != current) {
            enter(index);
        }
        if (cursor != null && Trees.order(key, cursor) <= 0) {
            return;                              // the crash-stride replay: already folded and durably flushed
        }
        if (fold(key)) {
            cursor = key;
            dirty = true;
        }
    }

    @Override
    public final void beforeCheckpoint(String committed) throws IOException {
        if (current < 0 || !dirty) {
            return;                              // nothing folded since the last flush (or an empty segment)
        }
        byte[] state = flush(current, generation, cursor);
        String key = stateKey(current);
        if (!store.writeVersioned(key, state, token)) {
            throw takenOver();
        }
        // Re-read for the next token, verifying the state is still ours, so a takeover is never clobbered.
        Optional<ArtifactStore.Versioned> written = store.readVersioned(key);
        if (written.isEmpty() || !holder.equals(holderOf(written.get().content()))) {
            throw takenOver();
        }
        token = written.get().token();
        dirty = false;
        flushed();
    }

    /** The segment a key is being folded into. */
    protected final WalkSegment segment() {
        return plan.get(current);
    }

    /** The failure that says the current segment was taken over by another holder. */
    protected final IOException takenOver() {
        return new IOException("the " + what + " partial of segment " + current + " was taken over");
    }

    /** The key a segment's state is stored under. */
    protected abstract String stateKey(int segment);

    /** The holder a stored state names, or {@code null} for one that does not parse. */
    protected abstract String holderOf(byte[] state);

    /** Forget everything folded in memory, on entering a segment. */
    protected abstract void reset();

    /** Adopt a segment's stored state, answering its cursor - or {@code null}, adopting nothing, when it was not
     *  written by this {@code generation} of the pass or does not parse. */
    protected abstract String adopt(byte[] state, long generation);

    /** Fold one key into the segment's partial, answering whether it was folded rather than skipped. */
    protected abstract boolean fold(String key) throws IOException;

    /** The state to store for the segment, written after anything it refers to. */
    protected abstract byte[] flush(int segment, long generation, String cursor) throws IOException;

    /** The flush landed: whatever was pending in it is now committed. */
    protected void flushed() {
    }

    private void enter(int index) throws IOException {
        reset();
        cursor = null;
        dirty = false;
        current = index;
        Optional<ArtifactStore.Versioned> stored = store.readVersioned(stateKey(index));
        token = stored.map(ArtifactStore.Versioned::token).orElse(null);
        if (stored.isPresent()) {
            cursor = adopt(stored.get().content(), generation);
        }
    }

    /** The planned range containing {@code key}; the ranges partition the root, so exactly one matches. */
    private int range(String key) throws IOException {
        if (current >= 0 && contains(plan.get(current), key)) {
            return current;
        }
        for (int index = 0; index < plan.size(); index++) {
            if (contains(plan.get(index), key)) {
                return index;
            }
        }
        throw new IOException("no planned " + what + " range contains " + key);
    }

    private static boolean contains(WalkSegment segment, String key) {
        return (segment.from() == null || Trees.order(segment.from(), key) <= 0)
                && (segment.to() == null || Trees.order(key, segment.to()) < 0);
    }
}
