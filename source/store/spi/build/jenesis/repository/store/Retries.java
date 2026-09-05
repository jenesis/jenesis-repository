package build.jenesis.repository.store;

import module java.base;

/**
 * The one retry policy for a compare-and-set through {@link ArtifactStore#writeVersioned}: how often a writer re-reads
 * the token and tries again before it gives the conflict up, and how long it waits between tries.
 *
 * <p>A lost compare-and-set is a peer writing the same key at the same moment - several identical publishes of one
 * version, the files of one revision arriving one after the other, a sweep and a publish meeting on a marker. Three
 * immediate retries were enough for two writers and not for four: with no pause between tries, the losers re-read
 * and re-write in lock step and lose again, and the writer that gave up answered a {@code 500} for a publish whose
 * bytes had landed. Twelve tries with a short, jittered pause between them spread the writers out; the pause stays
 * under a tenth of a second so the request that waits never waits long.
 *
 * <p>{@link #update} and {@link #tryUpdate} are the policy applied: read the key, let a {@link Mutation} decide the
 * new body from what is there, write it against the token that was read, and on a lost race back off and go round
 * again. Before they existed the loop was written out at some thirty-five sites with three, four, five, eight or
 * sixteen tries and no pause between them - the exact shape this class's own paragraph explains does not work - and
 * three different endings for the same exhaustion: throw, return silently, or spin. There are two endings now and a
 * caller picks one by name: {@link #update} throws, because a writer that gives up a compare-and-set has usually
 * lost something the caller must know about; {@link #tryUpdate} returns {@code false}, for the few writes whose loss
 * a later pass repairs - and a caller choosing it says in its javadoc which pass that is.
 *
 * <p>{@link #decide} and {@link #tryDecide} are the same policy for a write that has more to say than a body: the
 * {@link Decision} hands back a {@link Verdict} - write this body, keep the key as it is, or delete it - together with
 * a value the caller needs from the try that landed (the pointer that was replaced, whether a publish was the first,
 * the transition a re-fold is made from). Twenty-five loops still stood after the first sweep, most of them written
 * out only to keep such a value in a local; a loop that keeps a value is not a reason to keep a loop. A
 * {@link Verdict#delete deletion} is the store's unconditional {@link ArtifactStore#delete}, as every caller that
 * emptied a set and dropped its key already did: the store has no conditional delete, and a peer that lands between
 * the read and the delete has written into a key whose content the deciding try found empty.
 */
public final class Retries {

    /** How often a compare-and-set is tried before its conflict is given up. */
    public static final int COMPARE_AND_SET = 12;

    private Retries() {
    }

    /** Wait before the next try: a few milliseconds at first, doubling to at most a hundred, plus a little jitter so
     *  peers that lost together do not retry together. */
    public static void backoff(int attempt) {
        try {
            Thread.sleep(Math.min(100L, 2L << Math.min(attempt, 6)) + ThreadLocalRandom.current().nextLong(5L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * What a compare-and-set writes, decided from what the key holds at the moment of the read: the new body, or
     * {@code null} to leave the key as it is - the first-write-wins marker that is already there, the counter that
     * needs no change, the lease another holder now owns.
     */
    @FunctionalInterface
    public interface Mutation {

        byte[] apply(Optional<ArtifactStore.Versioned> current) throws IOException;
    }

    /**
     * One try of a compare-and-set that is not a plain store key - a document behind another versioned API - read,
     * changed and conditionally written by the caller; answers whether it landed (or found nothing to do).
     */
    @FunctionalInterface
    public interface Attempt {

        boolean tryOnce() throws IOException;
    }

    /**
     * What one try of {@link #decide} decided from the key's current content: a body to write, nothing to do, or the
     * key to delete - and the value the caller gets back from the try that lands.
     */
    public record Verdict<T>(byte[] body, boolean delete, T result) {

        /** Write {@code body} against the token that was read; {@code result} is the caller's if it lands. */
        public static <T> Verdict<T> write(byte[] body, T result) {
            return new Verdict<>(Objects.requireNonNull(body, "body"), false, result);
        }

        /** Leave the key as it is - nothing to change - and answer {@code result} at once. */
        public static <T> Verdict<T> keep(T result) {
            return new Verdict<>(null, false, result);
        }

        /** Delete the key - a set that emptied, a last statement that went - and answer {@code result}. */
        public static <T> Verdict<T> delete(T result) {
            return new Verdict<>(null, true, result);
        }
    }

    /** A {@link Mutation} that also decides between writing, keeping and deleting, and carries a value back. */
    @FunctionalInterface
    public interface Decision<T> {

        Verdict<T> decide(Optional<ArtifactStore.Versioned> current) throws IOException;
    }

    /**
     * Apply {@code mutation} to {@code key} under compare-and-set, re-reading and retrying with {@link #backoff} for
     * up to {@link #COMPARE_AND_SET} tries. Throws when every try lost, naming the key: a writer that gives the
     * conflict up has usually lost a record the caller must not pretend it kept.
     */
    public static void update(ArtifactStore store, String key, Mutation mutation) throws IOException {
        compareAndSet(key, () -> tryOnce(store, key, mutation));
    }

    /**
     * {@link #update}, answering {@code false} instead of throwing when every try lost. For the few writes a caller
     * can afford to lose because a later pass re-derives the key - a usage counter, a size roll-up - and such a
     * caller names that pass where it chooses this form, so the loss is a known repair and not a silent one.
     */
    public static boolean tryUpdate(ArtifactStore store, String key, Mutation mutation) throws IOException {
        return tryCompareAndSet(() -> tryOnce(store, key, mutation));
    }

    /**
     * {@link #update} for a write with a {@link Verdict}: the {@link Decision} is asked of every re-read until a try
     * lands, and the value it attached to that try is answered - {@code null} if that is what it attached. Throws
     * when every try lost, naming the key.
     */
    public static <T> T decide(ArtifactStore store, String key, Decision<T> decision) throws IOException {
        Optional<Verdict<T>> landed = tryDecide(store, key, decision);
        if (landed.isEmpty()) {
            throw new IOException("lost the compare-and-set on " + key + " " + COMPARE_AND_SET + " times running");
        }
        return landed.get().result();
    }

    /**
     * {@link #decide}, answering the verdict that landed - whose {@link Verdict#result} may be {@code null} - or empty
     * when every try lost, so a caller tells a landed nothing from exhaustion.
     */
    public static <T> Optional<Verdict<T>> tryDecide(ArtifactStore store, String key, Decision<T> decision)
            throws IOException {
        for (int tries = 0; tries < COMPARE_AND_SET; tries++) {
            Optional<ArtifactStore.Versioned> current = store.readVersioned(key);
            Verdict<T> verdict = decision.decide(current);
            if (verdict.delete()) {
                store.delete(key);
                return Optional.of(verdict);
            }
            if (verdict.body() == null
                    || store.writeVersioned(key, verdict.body(), current.map(ArtifactStore.Versioned::token).orElse(null))) {
                return Optional.of(verdict);
            }
            backoff(tries);
        }
        return Optional.empty();
    }

    /** {@link #update} for a compare-and-set the caller performs itself: {@code attempt} is tried until it lands,
     *  with {@link #backoff} between tries, and the exhaustion throws naming {@code what}. */
    public static void compareAndSet(String what, Attempt attempt) throws IOException {
        if (!tryCompareAndSet(attempt)) {
            throw new IOException("lost the compare-and-set on " + what + " " + COMPARE_AND_SET + " times running");
        }
    }

    /** {@link #tryUpdate} for a compare-and-set the caller performs itself. */
    public static boolean tryCompareAndSet(Attempt attempt) throws IOException {
        for (int tries = 0; tries < COMPARE_AND_SET; tries++) {
            if (attempt.tryOnce()) {
                return true;
            }
            backoff(tries);
        }
        return false;
    }

    private static boolean tryOnce(ArtifactStore store, String key, Mutation mutation) throws IOException {
        Optional<ArtifactStore.Versioned> current = store.readVersioned(key);
        byte[] body = mutation.apply(current);
        return body == null
                || store.writeVersioned(key, body, current.map(ArtifactStore.Versioned::token).orElse(null));
    }
}
