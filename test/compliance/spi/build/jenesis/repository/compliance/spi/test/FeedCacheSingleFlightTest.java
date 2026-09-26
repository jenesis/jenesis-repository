package build.jenesis.repository.compliance.spi.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.FeedCache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The single flight at the choke point: <em>whose</em> refresh a cold lookup waits for.
 *
 * <p>The cache's promise is that "a cold-cache burst collapses into single upstream calls in turn" - one query per
 * key, which is both what a consumer wants and the courtesy a metered vendor's rate limit asks for. The lock it was
 * implemented with was the cache's own monitor, which is a different promise: <em>one query at a time, full stop</em>.
 * Coordinate B's cold lookup queued behind coordinate A's although the two have nothing to do with each other.
 *
 * <p>That was merely slow until a failing refresh stopped being cheap. A fail-closed feed past its window
 * re-asks the vendor rather than re-serving what it drew, so during a vendor outage every gate thread queued on the
 * one monitor and <b>each paid the whole feed-policy budget in turn</b> - three attempts, up to a five-minute
 * whole-fetch deadline - for coordinates that were never related. An outage that should cost one slow request per
 * coordinate cost the entire gate, serially.
 *
 * <p>So these three legs are the property, not the implementation: an unrelated key is not held up, the same key is
 * still collapsed into one call, and a refresh that fails leaves its key askable again immediately rather than locked
 * or stranded in flight. The first fails on the pre-cache; the second is what a per-key fix must not lose.
 */
class FeedCacheSingleFlightTest {

    private static final Duration WINDOW = Duration.ofHours(6);

    /** How long a leg waits for something that should already have happened before calling it a hang - generous,
     *  because a slow machine must not turn this suite red, and only ever reached on failure. */
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    /** How long the unrelated key is given to answer while the first key sits in its loader. Short on purpose: on the
     *  old cache it cannot answer at all, and this is the wait that decides the leg. */
    private static final Duration UNRELATED = Duration.ofSeconds(10);

    private static final String SLOW = "org.example:slow";
    private static final String UNRELATED_KEY = "org.example:unrelated";

    private final Ticking clock = new Ticking();

    @Test
    void a_cold_lookup_is_not_queued_behind_an_unrelated_key() throws Exception {
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        FeedCache<String> cache = FeedCache.failClosed("licensed", key -> {
            if (SLOW.equals(key)) {
                reached.countDown();
                held(released);
            }
            return "advisories for " + key;
        }, WINDOW, clock);

        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<String> slow = threads.submit(() -> cache.get(SLOW));
            assertThat(reached.await(PATIENCE.toMillis(), TimeUnit.MILLISECONDS))
                    .as("the first lookup never reached its loader, so nothing here observed a queue at all")
                    .isTrue();

            // The vendor is now hanging on SLOW. A second gate thread screening an unrelated coordinate must reach
            // its own loader and answer; whether the first one ever returns is none of its business.
            Future<String> unrelated = threads.submit(() -> cache.get(UNRELATED_KEY));
            String answered;
            try {
                answered = unrelated.get(UNRELATED.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException queued) {
                throw new AssertionError("A cold lookup of " + UNRELATED_KEY + " did not complete within " + UNRELATED
                        + " while an unrelated cold lookup of " + SLOW + " sat in its loader. The single flight is per"
                        + " cache rather than per key, so during a vendor outage every gate thread queues on one"
                        + " monitor and each pays the whole feed-policy budget in turn", queued);
            }
            assertThat(answered).isEqualTo("advisories for " + UNRELATED_KEY);

            released.countDown();
            assertThat(slow.get(PATIENCE.toMillis(), TimeUnit.MILLISECONDS))
                    .as("and the key that was slow still answers once its vendor does")
                    .isEqualTo("advisories for " + SLOW);
        } finally {
            released.countDown();
            threads.shutdownNow();
        }
    }

    @Test
    void concurrent_lookups_of_the_same_key_still_collapse_into_one_upstream_call() throws Exception {
        // The property the lock existed for, and the one a per-key fix must not trade away: a metered vendor is asked
        // once for a key however many gate threads want it at that moment.
        AtomicInteger upstream = new AtomicInteger();
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        FeedCache<String> cache = FeedCache.failClosed("licensed", key -> {
            upstream.incrementAndGet();
            reached.countDown();
            held(released);
            return "advisories for " + key;
        }, WINDOW, clock);

        int callers = 4;
        ExecutorService threads = Executors.newFixedThreadPool(callers);
        try {
            List<Future<String>> answers = new ArrayList<>();
            for (int caller = 0; caller < callers; caller++) {
                answers.add(threads.submit(() -> cache.get(SLOW)));
            }
            assertThat(reached.await(PATIENCE.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            released.countDown();
            for (Future<String> answer : answers) {
                assertThat(answer.get(PATIENCE.toMillis(), TimeUnit.MILLISECONDS))
                        .as("every caller gets the one answer that was drawn")
                        .isEqualTo("advisories for " + SLOW);
            }
        } finally {
            released.countDown();
            threads.shutdownNow();
        }
        assertThat(upstream.get())
                .as("a burst on one key is one query - a caller that arrives while a key is being drawn waits for "
                        + "that draw, and one that arrives after it reads the entry; neither spends the vendor a "
                        + "second call")
                .isEqualTo(1);
    }

    @Test
    void a_refresh_that_failed_leaves_its_key_neither_locked_nor_in_flight() throws Exception {
        // The failure path is where a keyed flight goes wrong if it goes wrong: a key left registered would be
        // permanently unrefreshable, and a caller waiting on a flight nobody completes would never return.
        AtomicBoolean down = new AtomicBoolean(true);
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        FeedCache<String> cache = FeedCache.failClosed("licensed", key -> {
            if (down.get()) {
                reached.countDown();
                held(released);
                throw new IOException("the vendor is unreachable");
            }
            return "advisories for " + key;
        }, WINDOW, clock);

        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = threads.submit(() -> cache.get(SLOW));
            assertThat(reached.await(PATIENCE.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            Future<String> second = threads.submit(() -> cache.get(SLOW));
            released.countDown();

            for (Future<String> caller : List.of(first, second)) {
                assertThatExceptionOfType(ExecutionException.class)
                        .as("both callers learn the refresh did not land rather than waiting on it for ever")
                        .isThrownBy(() -> caller.get(PATIENCE.toMillis(), TimeUnit.MILLISECONDS))
                        .withRootCauseInstanceOf(IOException.class);
            }
        } finally {
            released.countDown();
            threads.shutdownNow();
        }

        // Nothing about the failure is remembered: the key is asked again on the very next lookup, so a blip costs
        // the publishes it overlaps and not a minute more.
        down.set(false);
        assertThat(cache.get(SLOW))
                .as("a key whose refresh failed is askable again immediately, not locked out by a flight nobody "
                        + "removed")
                .isEqualTo("advisories for " + SLOW);
    }

    /** Park in the loader until the test releases it, the way a vendor that has stopped answering does. */
    private static void held(CountDownLatch released) throws IOException {
        try {
            if (!released.await(PATIENCE.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException("the test never released this loader");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while held in the loader", e);
        }
    }
}
