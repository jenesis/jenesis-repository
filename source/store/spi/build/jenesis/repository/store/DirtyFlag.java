package build.jenesis.repository.store;

import module java.base;

/**
 * A coalescing flag under one store key: its <em>presence</em> is the whole signal - "something changed since the last
 * pass" - and every writer between two passes lands on the same object, so a burst of changes costs the next pass one
 * rebuild rather than one per change. It is a {@link DirtyIndexFeed} over a single fixed subject, and it is cleared the
 * way the feed clears a marker: {@link #peek read with its token} before the pass's walk, {@link #clearIf deleted
 * only while that token still stands} after the pass has committed, so a change that landed mid-pass re-wrote the flag,
 * changed its token, and survives for the next pass. A crash between the walk and the clear leaves the flag standing,
 * and the next pass rebuilds again: idempotent, and never a lost change.
 *
 * <p>The body is the instant of the latest change, kept for diagnostics and never read for a decision. The published
 * index's retraction flag was this exact shape, written out on the index with a javadoc that cited the feed's clear
 * discipline twice; the discipline lives here now.
 */
public final class DirtyFlag {

    private final ArtifactStore store;

    private final String key;

    public DirtyFlag(ArtifactStore store, String key) {
        this.store = Objects.requireNonNull(store, "store");
        this.key = Objects.requireNonNull(key, "key");
    }

    /** The store key the flag lives under, so a storage manifest can name it. */
    public String key() {
        return key;
    }

    /** Raise the flag, or re-raise it: a compare-and-set through {@link Retries}, so concurrent writers never lose the
     *  signal - they coalesce onto the one object, and the token moves with every write. */
    public void mark(Instant when) throws IOException {
        byte[] body = when.toString().getBytes(StandardCharsets.UTF_8);
        Retries.update(store, key, _ -> body);
    }

    /** The flag with its token, or empty when nothing is pending. Read before the pass's walk, so a change landing
     *  mid-pass - which re-writes the flag and moves its token - is not cleared by this pass's {@link #clearIf}. */
    public Optional<ArtifactStore.Versioned> peek() throws IOException {
        return store.readVersioned(key);
    }

    /** Lower the flag only while its token is still {@code token}, the one {@link #peek} answered before the walk.
     *  Called only after the pass's result has committed. */
    public void clearIf(Object token) throws IOException {
        Optional<ArtifactStore.Versioned> current = store.readVersioned(key);
        if (current.isPresent() && Objects.equals(current.get().token(), token)) {
            store.delete(key);
        }
    }
}
