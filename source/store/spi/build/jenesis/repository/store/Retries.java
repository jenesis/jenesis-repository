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
}
