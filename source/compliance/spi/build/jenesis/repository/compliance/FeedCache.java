package build.jenesis.repository.compliance;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.store.SingleFlight;

/**
 * The per-key TTL cache every metered (licensed, rate-limited) feed lookup sits behind - the KEV-style volatile-swap
 * idiom generalized to a keyed map, extracted from the Snyk reference implementation so each licensed sibling reuses
 * it instead of re-rolling it. A fresh entry answers without an upstream call, so a serve never pays for (or spends
 * quota on) a network query; refreshes are single-flight <em>per key</em>, so a cold-cache burst on one coordinate
 * collapses into a single upstream call, which is also the courtesy the vendor's rate limit asks for. The map is
 * capacity-bounded: past the cap the expired entries are dropped, and if every entry is still live the cache is
 * cleared outright - it is only a cache, a cleared entry merely re-queries.
 *
 * <h2>The single flight is per key, and that is the shape of an outage</h2>
 * The flight is keyed because the answers are: coordinate A's refresh has nothing to do with coordinate B's, and a
 * lock over the whole cache made B's cold lookup queue behind A's. That was merely slow until a failing refresh
 * stopped being cheap: since the earlier work a fail-closed feed past its window <em>re-asks the vendor</em>, so during an outage
 * every gate thread queued on one monitor and each paid the full feed policy budget in turn - three attempts and up to
 * a five-minute whole-fetch deadline, serially, for coordinates that had nothing to do with each other. An outage that
 * should cost one slow request per coordinate cost the whole gate.
 *
 * <p>So a refresh registers a {@link CompletableFuture} under its key: the first caller for a key does the upstream
 * work, any concurrent caller <em>for that same key</em> waits on its result rather than issuing a second query, and a
 * caller for a different key is not held up at all. <b>The structure needs no eviction</b>, which is the point of
 * choosing it over a lock per key: an entry exists only while a load for that key is actually running and is removed
 * in a {@code finally}, so the map is bounded by the number of threads inside a refresh at once rather than by the
 * key space, and neither a failed nor an abandoned load can leave a key locked or permanently in flight. What is
 * <em>not</em> per key is the bookkeeping - the entry store, the capacity sweep and the {@link FreshnessTracker}
 * marks - which is serialized because each is a check-then-act over a shared map; none of it reaches a vendor, so
 * nothing that can be slow is shared.
 *
 * <h2>What an <em>aged</em> entry is worth, and why the two answers differ</h2>
 * The window is the cache's whole promise: an entry inside it is an answer the source stands behind, and an entry past
 * it is not an answer at all until a refresh lands. The interesting case is the refresh that then <em>fails</em>, and
 * this cache had one answer for it - keep serving the last good value and re-extend it by {@link FreshnessTracker#RETRY} -
 * which is right for one half of the family and wrong for the other. It is now <b>declared per cache</b>
 * ({@link Aged}), with no default, because the two halves fail in opposite directions:
 *
 * <ul>
 * <li>{@link Aged#RAISED} - the <b>fail-closed</b> half ({@code AdvisorySource} clause 4). The failure is raised and
 *     the aged entry is dropped. An advisory list is load-bearing in its <em>emptiness</em>: the gate reads "no
 *     advisories" as "this package is clean", so an aged list is exactly as misleading as an outage for any advisory
 *     published since it was drawn - and a newly published advisory is the case clause 4 exists for. The aged list's
 *     positive half is not lost by raising, it is <em>subsumed</em>: a raise blocks the publish, which is a superset
 *     of everything the aged list would have blocked, so the raise strictly dominates serving it. For this half a
 *     stale answer and an outage really are the same thing, because they license the same action.</li>
 * <li>{@link Aged#SERVED} - the <b>fail-soft</b> half ({@code KnownExploitedSource} clause 6,
 *     {@code HealthSource} clause 7). The aged value keeps being served and the entry is re-extended by
 *     {@link FreshnessTracker#RETRY}, <em>with its original fetch instant</em>: the reading never claims the aged
 *     answer was just fetched. Here the aged value is real evidence that has merely got older - the CVEs a catalogue
 *     names are still actively exploited, a Scorecard moves over months - and discarding it for the neutral value
 *     would swap real evidence for none, in the <em>loosening</em> direction. How old is too old is the consumer's
 *     policy, read off {@link Freshness#refreshed()}; this cache only makes the age honest.</li>
 * </ul>
 *
 * <p>A failure with <em>nothing</em> to serve is raised whichever mode is declared - there is no aged answer to
 * choose about - so a gate consulting a cold, broken licensed feed still fails closed and a fail-soft source still
 * catches it into its own degraded answer.
 *
 * <h2>The staleness reading lives here, not beside it</h2>
 * {@link #freshness()} is this cache's own account of what it is serving, so a {@code FeedCache}-backed source hands
 * it straight out of {@link SignalSource#freshness()} instead of keeping a second flag that can disagree with the
 * entries. It is the shared {@link FreshnessTracker} derivation, stamped from this cache's own loads and from nothing
 * else - the instant is the last completed load of any key, and the reading is not authoritative while any key this
 * cache cannot currently answer for is inside its retry window.
 */
public final class FeedCache<T> {

    /** One keyed upstream lookup - the query <em>and</em> its parse, so a malformed licensed answer is a refresh
     *  failure (raised or aged-served, per the declared {@link Aged} mode) exactly like an unreachable one. */
    @FunctionalInterface
    public interface Loader<T> {
        T load(String key) throws IOException;
    }

    /**
     * What this cache does with an entry whose window has lapsed when the refresh that should have replaced it fails.
     * There is no default: a feed states which half of the family it belongs to at construction, so a new licensed
     * sibling cannot inherit the wrong one by saying nothing.
     */
    private enum Aged {

        /** Fail-closed: the failure is raised and the aged entry dropped, because the answer's emptiness is what a
         *  consumer acts on and nobody screened the key since the window lapsed. */
        RAISED,

        /** Fail-soft: the aged value is served with its original fetch instant and re-asked after
         *  {@link FreshnessTracker#RETRY}, because it is real evidence that has aged and the neutral value would be no
         *  evidence at all. */
        SERVED
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(FeedCache.class);

    private static final int CAP = 10_000;

    private record Cached(Object value, long until) {
    }

    private final String feed;
    private final Loader<T> loader;
    private final long ttl;
    private final Aged aged;
    private final Clock clock;
    private final ConcurrentMap<String, Cached> cache = new ConcurrentHashMap<>();

    /** The reading, derived from this cache's own loads: the instant of the last completed one and the keys it
     *  currently cannot answer for. */
    private final FreshnessTracker fetches;

    /** The loads currently running, one per key - the per-key single flight, bounded by the threads inside
     *  {@link #refresh} at once, not by the key space. */
    private final SingleFlight<String, T> flights = new SingleFlight<>();

    /** How long a caller waits on another caller's load of the same key before giving up on it: well past any
     *  vendor timeout the loader carries, so a follower only ever gives up on a load that is itself stuck. */
    private static final Duration FOLLOW = Duration.ofMinutes(10);

    private FeedCache(String feed, Loader<T> loader, Duration ttl, Clock clock, Aged aged) {
        this.feed = Objects.requireNonNull(feed, "feed");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.ttl = ttl.toMillis();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.aged = aged;
        this.fetches = new FreshnessTracker(clock);
    }

    /**
     * A cache for a <b>fail-closed</b> signal - every {@code AdvisorySource}. An aged entry whose refresh fails is
     * dropped and the failure raised, so the source never answers a list nobody screened; see {@link Aged#RAISED}.
     */
    public static <T> FeedCache<T> failClosed(String feed, Loader<T> loader, Duration ttl, Clock clock) {
        return new FeedCache<>(feed, loader, ttl, clock, Aged.RAISED);
    }

    /**
     * A cache for a <b>fail-soft</b> signal - a known-exploited membership index, a maintainer-health source. An aged
     * entry whose refresh fails keeps answering with its original fetch instant; see {@link Aged#SERVED}.
     */
    public static <T> FeedCache<T> failSoft(String feed, Loader<T> loader, Duration ttl, Clock clock) {
        return new FeedCache<>(feed, loader, ttl, clock, Aged.SERVED);
    }

    @SuppressWarnings("unchecked")
    public T get(String key) {
        Cached cached = cache.get(key);
        if (cached != null && clock.millis() < cached.until()) {
            return (T) cached.value();
        }
        return refresh(key);
    }

    /**
     * What this cache is currently serving, in the shape {@link SignalSource#freshness()} hands out: the instant of
     * the last completed load and whether a consumer may act on what it gets. Renders only - no lookup, no stamp
     * moves, and two reads with nothing in between agree.
     */
    public Freshness freshness() {
        return fetches.freshness();
    }

    /**
     * The keyed single flight. The first caller for a key owns the load; a concurrent caller for the
     * <em>same</em> key waits on that one result instead of issuing a second query, and a caller for any other key
     * proceeds without waiting for either. The flight is registered before the load starts and removed once it
     * finishes however it finishes, so a failure leaves the key askable again immediately - and a load that ends in
     * neither an answer nor a failure still completes the flight, so no waiter is left parked on one.
     */
    private T refresh(String key) {
        SingleFlight.Outcome<T> outcome;
        try {
            outcome = flights.run(key, () -> fetch(key), FOLLOW);
        } catch (IOException impossible) {
            throw new UncheckedIOException("a feed load declares no IOException", impossible);   // fetch throws none
        }
        return switch (outcome) {
            case SingleFlight.Led<T> led -> led.value();
            case SingleFlight.Followed<T> followed -> followed.value();   // the value the owning caller drew, as our own
            case SingleFlight.Failed<T> failed -> throw raised(key, failed.failure());
            case SingleFlight.Overdue<T> _ -> throw new IllegalStateException("The in-flight " + feed + " refresh of "
                    + key + " did not answer within " + FOLLOW);
        };
    }

    /** The failure the owning caller raised, raised here for the same reason: a fail-closed feed must not answer,
     *  and a fail-soft source catches this into its own degraded answer exactly as it catches an outage. An
     *  interrupted wait is raised the same way, its flag already set again. */
    private RuntimeException raised(String key, Throwable cause) {
        if (cause instanceof RuntimeException raised) {
            return raised;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        if (cause instanceof InterruptedException) {
            return new IllegalStateException("Interrupted while waiting for the in-flight " + feed
                    + " refresh of " + key, cause);
        }
        return new IllegalStateException("The in-flight " + feed + " refresh of " + key + " failed", cause);
    }

    /** One key's load, run by the caller that owns its flight. */
    @SuppressWarnings("unchecked")
    private T fetch(String key) {
        Cached cached = cache.get(key);
        if (cached != null && clock.millis() < cached.until()) {
            // A flight for this key landed while this caller was reaching for one of its own.
            return (T) cached.value();
        }
        T value;
        try {
            value = loader.load(key);
        } catch (IOException e) {
            return failed(key, cached, new UncheckedIOException("Failed to query " + feed + " for " + key, e));
        } catch (RuntimeException e) {
            return failed(key, cached, e);
        }
        fetches.fetched(key);            // this key answered, which is the only success that speaks for this key
        store(key, new Cached(value, clock.millis() + ttl));
        return value;
    }

    /**
     * The refresh did not land. A {@link Aged#SERVED} cache with an aged entry keeps serving it - the instant stays
     * the one the value was really drawn at, so the age is visible rather than laundered - and everything else raises:
     * a fail-closed feed may not answer a list nobody screened, and a cache with nothing to keep has no answer to give
     * whatever its mode.
     */
    @SuppressWarnings("unchecked")
    private T failed(String key, Cached previous, RuntimeException failure) {
        if (aged == Aged.SERVED && previous != null) {
            LOGGER.warn("Could not refresh the " + feed + " answer for " + key + "; serving the answer already drawn"
                    + " for it, which keeps its own fetch instant, and retrying shortly", failure);
            store(key, new Cached(previous.value(), clock.millis() + FreshnessTracker.RETRY.toMillis()));
            return (T) previous.value();
        }
        // Nothing this source may serve for the key, so the key stands for "could not answer" until it answers again
        // or its window lapses, and the aged entry - if there was one - goes rather than sitting in the map as an
        // answer that must never be given.
        fetches.failed(key);
        cache.remove(key);
        throw failure;
    }

    /** Store one entry, sweeping the cache back under its cap first. Serialized against the other stores because the
     *  cap is a check-then-act over a shared map; it is map work only, never a vendor call. */
    private synchronized void store(String key, Cached entry) {
        if (cache.size() >= CAP) {
            long now = clock.millis();
            cache.values().removeIf(cached -> now >= cached.until());
            if (cache.size() >= CAP) {
                cache.clear();
            }
        }
        cache.put(key, entry);
    }
}
