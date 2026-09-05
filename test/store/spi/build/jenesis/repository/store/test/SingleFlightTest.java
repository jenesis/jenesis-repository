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
        try (ExecutorService pool = Executors.newFixedThreadPool(1)) {
            Future<SingleFlight.Outcome<String>> leader = pool.submit(() -> flights.run("k", work, PATIENCE));
            assertThat(leading.await(10, TimeUnit.SECONDS)).isTrue();
            List<Follower> followers = followers(8, () -> flights.run("k", work, PATIENCE));
            awaitParked(followers);
            release.countDown();
            assertThat(leader.get(10, TimeUnit.SECONDS)).isEqualTo(new SingleFlight.Led<>("built"));
            for (Follower follower : followers) {
                assertThat(follower.result()).isEqualTo(new SingleFlight.Followed<>("built"));
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
        try (ExecutorService pool = Executors.newFixedThreadPool(1)) {
            Future<SingleFlight.Outcome<String>> leader = pool.submit(() -> flights.run("k", work, PATIENCE));
            assertThat(leading.await(10, TimeUnit.SECONDS)).isTrue();
            List<Follower> followers = followers(3, () -> flights.run("k", work, PATIENCE));
            awaitParked(followers);
            release.countDown();
            assertThatThrownBy(() -> leader.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(AssertionError.class);
            for (Follower follower : followers) {
                SingleFlight.Outcome<String> outcome = follower.result();
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
        try (ExecutorService pool = Executors.newFixedThreadPool(1)) {
            Future<SingleFlight.Outcome<String>> leader = pool.submit(() -> flights.run("k", work, PATIENCE));
            assertThat(leading.await(10, TimeUnit.SECONDS)).isTrue();
            List<Follower> followers = followers(1, () -> flights.run("k", work, PATIENCE));
            awaitParked(followers);
            release.countDown();
            assertThatThrownBy(() -> leader.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(IOException.class);
            SingleFlight.Outcome<String> outcome = followers.getFirst().result();
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

    /** A caller on its own thread, so the test can see it park inside the flight's timed wait before it releases the
     *  leader: a follower submitted to a pool could arrive after the leader had finished and lead a second flight of
     *  its own, which is what a first version of these tests did about one run in ten. */
    private record Follower(Thread thread, AtomicReference<SingleFlight.Outcome<String>> outcome,
                            AtomicReference<Throwable> failure) {

        SingleFlight.Outcome<String> result() throws Exception {
            thread.join(10_000);
            if (failure.get() != null) {
                throw new AssertionError("a follower failed instead of receiving an outcome", failure.get());
            }
            return outcome.get();
        }
    }

    private static List<Follower> followers(int count, Callable<SingleFlight.Outcome<String>> call) {
        List<Follower> followers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            AtomicReference<SingleFlight.Outcome<String>> outcome = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                try {
                    outcome.set(call.call());
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            thread.start();
            followers.add(new Follower(thread, outcome, failure));
        }
        return followers;
    }

    /** Every follower has parked in the flight's timed wait - the only timed wait on its path while the leader holds
     *  the flight - so releasing the leader now frees exactly these waiters. */
    private static void awaitParked(List<Follower> followers) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        for (Follower follower : followers) {
            while (follower.thread().getState() != Thread.State.TIMED_WAITING) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("a follower never parked in the flight's wait: " + follower.thread().getState());
                }
                Thread.onSpinWait();
            }
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
