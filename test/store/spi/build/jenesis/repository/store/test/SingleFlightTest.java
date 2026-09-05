package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.SingleFlight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SingleFlight}: one run per key with waiters, and - the case two of its four predecessors got wrong - an
 * {@code Error} out of the leader frees every waiter instead of parking them for ever. The leader gets its work's
 * value or exception; a waiter gets the value, the failure, or overdue when the leader outlives its patience.
 */
class SingleFlightTest {

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    @Test
    void waiters_take_the_leaders_value_and_the_flight_is_gone_afterwards() throws Exception {
        SingleFlight<String, String> flights = new SingleFlight<>();
        CountDownLatch leading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        SingleFlight.Work<String> work = () -> {
            runs.incrementAndGet();
            leading.countDown();
            await(release);
            return "built";
        };
        try (ExecutorService pool = Executors.newFixedThreadPool(9)) {
            Future<SingleFlight.Outcome<String>> leader = pool.submit(() -> flights.run("k", work, PATIENCE));
            assertThat(leading.await(10, TimeUnit.SECONDS)).isTrue();
            List<Future<SingleFlight.Outcome<String>>> followers = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                followers.add(pool.submit(() -> flights.run("k", work, PATIENCE)));
            }
            while (flights.inFlight() != 1) {
                Thread.onSpinWait();
            }
            release.countDown();
            assertThat(leader.get(10, TimeUnit.SECONDS)).isEqualTo(new SingleFlight.Led<>("built"));
            for (Future<SingleFlight.Outcome<String>> follower : followers) {
                assertThat(follower.get(10, TimeUnit.SECONDS)).isEqualTo(new SingleFlight.Followed<>("built"));
            }
        }
        assertThat(runs.get()).as("the work ran once for nine callers").isEqualTo(1);
        assertThat(flights.inFlight()).as("a finished flight leaves the map").isZero();
        assertThat(flights.run("k", () -> "again", PATIENCE)).as("the next caller leads again")
                .isEqualTo(new SingleFlight.Led<>("again"));
    }

    @Test
    void an_error_out_of_the_leader_frees_every_waiter() throws Exception {
        // Two of the four maps this replaces caught IOException | RuntimeException and completed the future in the
        // catch, so an Error - an OutOfMemoryError from a rebuild, an AssertionError - left it incomplete and every
        // waiter joined for ever. This is the mutation that proves the one finally bites.
        SingleFlight<String, String> flights = new SingleFlight<>();
        CountDownLatch leading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SingleFlight.Work<String> work = () -> {
            leading.countDown();
            await(release);
            throw new AssertionError("the leader died");
        };
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            Future<SingleFlight.Outcome<String>> leader = pool.submit(() -> flights.run("k", work, PATIENCE));
            assertThat(leading.await(10, TimeUnit.SECONDS)).isTrue();
            List<Future<SingleFlight.Outcome<String>>> followers = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                followers.add(pool.submit(() -> flights.run("k", work, PATIENCE)));
            }
            while (flights.inFlight() != 1) {
                Thread.onSpinWait();
            }
            release.countDown();
            assertThatThrownBy(() -> leader.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(AssertionError.class);
            for (Future<SingleFlight.Outcome<String>> follower : followers) {
                SingleFlight.Outcome<String> outcome = follower.get(10, TimeUnit.SECONDS);
                assertThat(outcome).isInstanceOf(SingleFlight.Failed.class);
                assertThat(((SingleFlight.Failed<String>) outcome).failure()).isInstanceOf(AssertionError.class);
            }
        }
        assertThat(flights.inFlight()).isZero();
    }

    @Test
    void the_leaders_io_exception_reaches_the_leader_and_the_waiters_as_a_failure() throws Exception {
        SingleFlight<String, String> flights = new SingleFlight<>();
        CountDownLatch leading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SingleFlight.Work<String> work = () -> {
            leading.countDown();
            await(release);
            throw new IOException("the store is down");
        };
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<SingleFlight.Outcome<String>> leader = pool.submit(() -> flights.run("k", work, PATIENCE));
            assertThat(leading.await(10, TimeUnit.SECONDS)).isTrue();
            Future<SingleFlight.Outcome<String>> follower = pool.submit(() -> flights.run("k", work, PATIENCE));
            while (flights.inFlight() != 1) {
                Thread.onSpinWait();
            }
            release.countDown();
            assertThatThrownBy(() -> leader.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(IOException.class);
            SingleFlight.Outcome<String> outcome = follower.get(10, TimeUnit.SECONDS);
            assertThat(outcome).isInstanceOf(SingleFlight.Failed.class);
            assertThat(((SingleFlight.Failed<String>) outcome).failure()).hasMessage("the store is down");
        }
    }

    @Test
    void a_waiter_whose_patience_runs_out_is_told_the_leader_is_overdue() throws Exception {
        SingleFlight<String, String> flights = new SingleFlight<>();
        CountDownLatch leading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SingleFlight.Work<String> work = () -> {
            leading.countDown();
            await(release);
            return "late";
        };
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<SingleFlight.Outcome<String>> leader = pool.submit(() -> flights.run("k", work, PATIENCE));
            assertThat(leading.await(10, TimeUnit.SECONDS)).isTrue();
            SingleFlight.Outcome<String> outcome = flights.run("k", work, Duration.ofMillis(100));
            assertThat(outcome).as("the waiter did not wait the leader out").isEqualTo(new SingleFlight.Overdue<>());
            release.countDown();
            assertThat(leader.get(10, TimeUnit.SECONDS)).isEqualTo(new SingleFlight.Led<>("late"));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
