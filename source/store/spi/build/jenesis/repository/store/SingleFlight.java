package build.jenesis.repository.store;

import module java.base;

/**
 * One piece of work per key at a time, in this process: the first caller for a key runs it and every caller that
 * arrives while it runs waits for that run instead of starting its own. A burst of first readers of an absent listing
 * used to generate it ten times over; fifty conditional reads against an absent identity rollup each walked the
 * coordinate set; a proxy miss for one path fetched it once per concurrent reader; a feed refresh for one key hit the
 * vendor once per caller. Each of those wrote this map of futures for itself - four copies - and two of them caught
 * {@code IOException | RuntimeException} around the leader's work and completed the future in the catch, so an
 * {@code Error} out of the leader left the future incomplete and every waiter parked on it for ever. The feed cache
 * had found that and completed an undone flight in its {@code finally}; the other three had not. One implementation,
 * one {@code finally}.
 *
 * <p>The leader gets its work's value or its exception, exactly as if it had called the work itself. A waiter gets an
 * {@link Outcome}: {@link Followed} with the leader's value, {@link Failed} with what the leader threw, or
 * {@link Overdue} when the leader outlived the wait the waiter allowed - so a hung leader (a store that never answers)
 * holds no waiter beyond that bound, and each caller decides for itself what a follower does with the answer: take the
 * value, re-probe the store, fetch for itself. The map holds an entry only while a flight runs, so it is bounded by
 * the callers inside {@link #run} at once, never by the key space; {@link #inFlight} is for a caller that wants a cap
 * on that as well.
 */
public final class SingleFlight<K, V> {

    /** What a caller got from {@link #run}. */
    public sealed interface Outcome<V> permits Led, Followed, Failed, Overdue {
    }

    /** This caller ran the work; {@code value} is what it produced. */
    public record Led<V>(V value) implements Outcome<V> {
    }

    /** Another caller ran the work while this one waited; {@code value} is what it produced. */
    public record Followed<V>(V value) implements Outcome<V> {
    }

    /** Another caller ran the work and it failed with {@code failure} - or was interrupted waiting, in which case the
     *  failure is the {@link InterruptedException} and the thread's interrupt flag is set again. */
    public record Failed<V>(Throwable failure) implements Outcome<V> {
    }

    /** Another caller is still running the work after the wait this caller allowed. */
    public record Overdue<V>() implements Outcome<V> {
    }

    /** The work one flight does. */
    @FunctionalInterface
    public interface Work<V> {

        V call() throws IOException;
    }

    private final ConcurrentMap<K, CompletableFuture<V>> flights = new ConcurrentHashMap<>();

    /**
     * Run {@code work} for {@code key} if nobody is, otherwise wait at most {@code atMost} for the run in flight. The
     * leader's exception propagates to the leader; a flight is completed on every exit, so a waiter never waits on a
     * run that has ended.
     */
    public Outcome<V> run(K key, Work<V> work, Duration atMost) throws IOException {
        CompletableFuture<V> mine = new CompletableFuture<>();
        CompletableFuture<V> running = flights.putIfAbsent(key, mine);
        if (running != null) {
            return follow(running, atMost);
        }
        try {
            V value = work.call();
            mine.complete(value);
            return new Led<>(value);
        } catch (IOException | RuntimeException | Error failure) {
            mine.completeExceptionally(failure);
            throw failure;
        } finally {
            flights.remove(key, mine);
            if (!mine.isDone()) {
                // Nothing above lets this happen, and the waiters already hold the reference, so removing the entry
                // would not reach them: complete it, so a future shape of failure still frees them.
                mine.completeExceptionally(new IllegalStateException("the flight for " + key + " ended without an answer"));
            }
        }
    }

    /** How many flights are running in this process right now. */
    public int inFlight() {
        return flights.size();
    }

    private Outcome<V> follow(CompletableFuture<V> running, Duration atMost) {
        try {
            return new Followed<>(running.get(atMost.toMillis(), TimeUnit.MILLISECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Failed<>(interrupted);
        } catch (TimeoutException _) {
            return new Overdue<>();
        } catch (ExecutionException failed) {
            return new Failed<>(failed.getCause());
        }
    }
}
