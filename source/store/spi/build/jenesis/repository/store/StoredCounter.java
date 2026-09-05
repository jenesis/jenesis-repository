package build.jenesis.repository.store;

import module java.base;

/**
 * A running total under one store key, moved by signed deltas under compare-and-set and recomputed from truth by a
 * pass: the bytes a tenant has stored against its quota, the bytes published beneath a browse folder. The counter is a
 * plain decimal string, floored at zero, and a value that does not parse reads as zero - a truncated write or a hand
 * edit never throws through the fold that maintains it.
 *
 * <p>{@link #add} is best-effort by design: a delta that loses every retry is dropped and {@code false} says so, and the
 * caller logs the drop naming the pass that recomputes the total - the quota's {@code recompute}, the browse's
 * {@code rollUpSizes} - so a counter drifting under sustained contention is visible before the pass corrects it, not a
 * silent surprise. It fails toward the last landed total, never toward a wrong one, which is what makes the drift
 * acceptable where an identity rollup's is not: a stale byte count is a stale number on a screen, a stale identity is
 * a wrong {@code 304}. That difference in failure model is why this is not the rollup's class and never will be.
 *
 * <p>The quota decorator and the subtree-size observer each wrote this - the same parse, the same floor, the same
 * {@link Retries#tryUpdate}, the same warning shape - and the observer's javadoc said "exactly as the quota does" three
 * times over. One class, one test.
 */
public final class StoredCounter {

    private final ArtifactStore store;

    private final String key;

    public StoredCounter(ArtifactStore store, String key) {
        this.store = Objects.requireNonNull(store, "store");
        this.key = Objects.requireNonNull(key, "key");
    }

    /** The store key the counter lives under, so a storage manifest can name it. */
    public String key() {
        return key;
    }

    /** The current total: zero when never counted, or when the stored value does not parse. */
    public long read() throws IOException {
        Optional<ArtifactStore.Versioned> stored = store.readVersioned(key);
        return stored.isEmpty() ? 0L : parse(stored.get().content());
    }

    /** Move the total by {@code delta}, floored at zero, retrying a lost compare-and-set through {@link Retries};
     *  {@code false} when every try lost and the delta was dropped for the recomputing pass to heal. */
    public boolean add(long delta) throws IOException {
        return Retries.tryUpdate(store, key, stored -> {
            long current = stored.isEmpty() ? 0L : parse(stored.get().content());
            return Long.toString(Math.max(0L, current + delta)).getBytes(StandardCharsets.UTF_8);
        });
    }

    /** Store a total recomputed from truth, whatever the counter held - the pass's authoritative correction. A lost
     *  race is left to the next pass, as the caller's own last-writer-wins write always was. */
    public void set(long total) throws IOException {
        byte[] body = Long.toString(Math.max(0L, total)).getBytes(StandardCharsets.UTF_8);
        Retries.tryUpdate(store, key, _ -> body);
    }

    private static long parse(byte[] content) {
        try {
            return Long.parseLong(new String(content, StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException _) {
            return 0L;
        }
    }
}
