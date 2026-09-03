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
