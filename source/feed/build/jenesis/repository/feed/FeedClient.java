package build.jenesis.repository.feed;

import module java.base;
import module org.slf4j;

/**
 * The one bounded client every externally-sourced HTTP JSON feed fetches through - an advisory API, a known-exploited
 * catalogue, an exploit-probability model, a maintainer-health dataset. It owns what is the same for every vendor:
 * timeouts and the whole-fetch deadline, header (authentication) injection, the non-200 branch, cursor pagination
 * bounded by a page cap that fails visibly, a response byte cap, a retry schedule, the fail-closed / fail-soft policy,
 * the clean self-skip of an unconfigured feed, and - through {@link FeedSnapshots} - a mirrored catalogue committed
 * with its staleness stamp. The vendor's URL shape, credential, wire format and field mapping stay in the feed module,
 * which reaches this client through the {@link FeedRequest}s it builds and the {@link Reader} it hands in.
 *
 * <p><strong>Nothing global is discovered.</strong> The transport, the clock, the pause and the tenant-scoped store all
 * arrive as arguments, so a feed can be driven from recorded responses and a read path asserted to make no request.
 *
 * <h3>Why a partial answer cannot escape</h3>
 * A caller supplies a {@link Supplier} of {@link Reader}: one fresh accumulator per attempt, and
 * {@link Reader#complete()} is called only once every page of that attempt is drawn. A fetch that hits a cap, a bad
 * status, the deadline or the attempt limit drops its half-filled accumulator, so no caller ever receives a value
 * assembled from some of the pages, and a retry never resumes another attempt's state.
 *
 * {@snippet :
 * FeedClient client = FeedClient.of("kev", FeedTransport.jdk(Duration.ofSeconds(10)), FeedPolicy.closed());
 * FeedClient.Answer<List<Advisory>> answer = client.fetch(FeedRequest.get(uri).bearer(token), MyReader::new);
 * }
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> Immutable and safe to share, as thread-safe as its {@link FeedTransport}. It does not
 *       serialise concurrent fetches: a feed that must collapse a burst does that in front of the client.</li>
 *   <li><b>Idempotency / replay.</b> A fetch mutates nothing, so it may be repeated. A retry restarts from the first
 *       request with a fresh reader, so no page is folded twice. {@link #refresh} of an unchanged catalogue commits the
 *       same content-addressed body and only advances the stamp.</li>
 *   <li><b>Absence sentinel.</b> Every call answers an {@link Answer}, never {@code null}, and a {@code null} from a
 *       {@link Reader} fails loudly. Unconfigured answers {@link Status#SKIPPED} with the reason, a degraded fetch
 *       {@link Status#DEGRADED} with the failure, and only {@link Status#FETCHED} carries a value.</li>
 *   <li><b>Selection failure.</b> A feed whose required configuration is unset builds an {@link #unconfigured} client
 *       that self-skips, touching neither network nor store, and is never "a feed that answers nothing".</li>
 *   <li><b>Streaming.</b> A body reaches a {@link Reader} as an {@link java.io.InputStream} capped at
 *       {@link FeedPolicy#maxResponseBytes()}; only the reduced snapshot a {@code byte[]} reader yields is held,
 *       bounded by {@link FeedPolicy#maxSnapshotBytes()}. There is no whole-body reader, since every feed would use it
 *       and spend heap proportional to what the vendor sent.</li>
 *   <li><b>Tenant scoping.</b> {@link #refresh} writes only through the {@link FeedSnapshots} it is handed, over an
 *       already tenant-scoped store; the client never scopes or discovers a store.</li>
 *   <li><b>Error visibility.</b> Nothing is swallowed. Under {@link FeedPolicy.FailMode#CLOSED} every failure is thrown
 *       as a {@link FeedException} naming the feed, reason, status and attempts; under {@link FeedPolicy.FailMode#SOFT}
 *       it is returned as {@link Status#DEGRADED} with that exception and logged once - an absent ranking aid, never an
 *       advisory answer that reads as clean.</li>
 *   <li><b>Read purity.</b> This is the write half of a feed. A read path renders {@link FeedSnapshots#current()} and
 *       {@link FeedSnapshots#open}, which reach no network.</li>
 *   <li><b>Staleness.</b> {@link #refresh} commits the fetch instant in the same store object as the snapshot it
 *       stamps, so every node and every restart sees the same "last refreshed".</li>
 *   <li><b>Lifecycle / ownership.</b> The transport, the clock and the store belong to the caller, which closes them.
 *       The client starts no thread and caches nothing between calls.</li>
 *   <li><b>Ordering / concurrency.</b> Pages are drawn strictly in cursor order on the calling thread, one at a time.
 *       Two concurrent {@link #refresh} calls are arbitrated by the snapshot pointer's compare-and-set, the loser
 *       adopting the winner's stamp.</li>
 *   <li><b>Bounded work / cancellation.</b> Every cap has a named outcome: {@link FeedPolicy#maxPages()}
 *       ({@link FeedException.Reason#PAGE_CAP}), {@link FeedPolicy#maxResponseBytes()} per body
 *       ({@link FeedException.Reason#RESPONSE_CAP}), {@link FeedPolicy#maxSnapshotBytes()}
 *       ({@link FeedException.Reason#SNAPSHOT_CAP}), {@link FeedPolicy#maxAttempts()},
 *       {@link FeedPolicy#requestTimeout()} per request and {@link FeedPolicy#deadline()} for the whole fetch
 *       ({@link FeedException.Reason#DEADLINE}). Reaching a cap always fails, never answers incompletely; under
 *       {@link FeedPolicy.FailMode#SOFT} the prior-good snapshot keeps serving. An interrupt ends the fetch promptly as
 *       {@link FeedException.Reason#INTERRUPTED} and restores the flag.</li>
 *   <li><b>Durability / delivery.</b> {@link #fetch} writes nothing. {@link #refresh}'s commit point is the snapshot
 *       pointer's compare-and-set, after the body is durable, so the visible states are the previous snapshot and the
 *       new one. An incomplete refresh leaves the prior-good snapshot and its instant untouched and moves
 *       {@code nextRefreshAt} by {@link FeedPolicy#retryInterval()}; it heals by being retried on schedule.</li>
 * </ol>
 */
public final class FeedClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(FeedClient.class);

    private final String feed;
    private final FeedTransport transport;
    private final FeedPolicy policy;
    private final Clock clock;
    private final Pause pause;
    private final String unconfigured;

    private FeedClient(String feed,
                       FeedTransport transport,
                       FeedPolicy policy,
                       Clock clock,
                       Pause pause,
                       String unconfigured) {
        this.feed = feed;
        this.transport = transport;
        this.policy = policy;
        this.clock = clock;
        this.pause = pause;
        this.unconfigured = unconfigured;
    }

    /** A client over the system clock, sleeping between retries. */
    public static FeedClient of(String feed, FeedTransport transport, FeedPolicy policy) {
        return of(feed, transport, policy, Clock.systemUTC(), Pause.sleeping());
    }

    /** A client with the clock and the retry pause injected, so a backoff schedule and a deadline are asserted without
     *  waiting. */
    public static FeedClient of(String feed, FeedTransport transport, FeedPolicy policy, Clock clock, Pause pause) {
        if (feed == null || feed.isBlank()) {
            throw new IllegalArgumentException("A feed client needs the feed's name");
        }
        return new FeedClient(feed.strip(),
                Objects.requireNonNull(transport, "transport"),
                Objects.requireNonNull(policy, "policy"),
                Objects.requireNonNull(clock, "clock"),
                Objects.requireNonNull(pause, "pause"),
                null);
    }

    /**
     * The self-skip for a feed whose configuration is incomplete: every call answers {@link Status#SKIPPED} naming what
     * is missing and touches neither network nor store, so it is never mistaken for a feed that answered nothing.
     *
     * @param missing the configuration keys that are unset, named in the skip reason
     */
    public static FeedClient unconfigured(String feed, String... missing) {
        if (feed == null || feed.isBlank()) {
            throw new IllegalArgumentException("A feed client needs the feed's name");
        }
        List<String> keys = List.of(missing);
        return new FeedClient(feed.strip(), null, FeedPolicy.soft(), Clock.systemUTC(), Pause.sleeping(),
                keys.isEmpty()
                        ? "the " + feed.strip() + " feed is not configured"
                        : "the " + feed.strip() + " feed is not configured: " + String.join(", ", keys) + " unset");
    }

    /** The feed's name - its attribution key, the same name its provider answers to. */
    public String feed() {
        return feed;
    }

    /** The bounds and behaviour this client runs under. */
    public FeedPolicy policy() {
        return policy;
    }

    /** Whether this client can fetch at all, or is the {@link #unconfigured} self-skip sentinel. */
    public boolean configured() {
        return unconfigured == null;
    }

    /**
     * Draw a feed answer: send {@code first}, hand each response to a fresh {@link Reader}, follow the cursor it
     * returns until none, and answer with what {@link Reader#complete()} yields.
     *
     * @param first the first request, with the vendor's credential on it
     * @param reader builds a fresh accumulator per attempt, so a partial answer is unrepresentable and a retry safe
     * @return {@link Status#FETCHED} with the value, {@link Status#SKIPPED} when unconfigured, or
     *     {@link Status#DEGRADED} when the policy fails soft
     * @throws FeedException when the policy fails closed and the fetch did not complete
     */
    public <T> Answer<T> fetch(FeedRequest first, Supplier<? extends Reader<T>> reader) throws FeedException {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(reader, "reader");
        if (!configured()) {
            return Answer.skipped(unconfigured);
        }
        Instant deadline = clock.instant().plus(policy.deadline());
        FeedException last = null;
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            if (attempt > 1) {
                Duration delay = policy.delayBefore(attempt,
                        last == null ? Optional.empty() : last.retryAfter());
                if (clock.instant().plus(delay).isAfter(deadline)) {
                    last = failure(FeedException.Reason.DEADLINE, 0, attempt - 1,
                            "the retry backoff of " + delay + " would run past the fetch deadline", last);
                    break;
                }
                try {
                    pause.pause(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    last = failure(FeedException.Reason.INTERRUPTED, 0, attempt - 1,
                            "interrupted while backing off before a retry", e);
                    break;
                }
            }
            try {
                return Answer.fetched(draw(first, reader.get(), deadline, attempt));
            } catch (FeedException e) {
                last = e;
                if (!last.retryable() || !clock.instant().isBefore(deadline)) {
                    break;
                }
            }
        }
        return failed(last);
    }

    /**
     * Refresh a mirrored catalogue: fetch it as {@link #fetch} does, then commit the snapshot together with its
     * staleness stamp through {@code snapshots}.
     * <ul>
     *   <li><b>Complete fetch</b> - the body is written and the pointer moves by compare-and-set; the answer carries
     *       the new {@link FeedSnapshots.Refresh}.</li>
     *   <li><b>Any incomplete fetch</b> - nothing is committed; the prior-good snapshot and its instant stand, and only
     *       {@code nextRefreshAt} moves by {@link FeedPolicy#retryInterval()}. The failure is thrown (fail-closed) or
     *       returned as {@link Status#DEGRADED} (fail-soft).</li>
     *   <li><b>Unconfigured</b> - {@link Status#SKIPPED}; the store is not touched.</li>
     * </ul>
     *
     * @param snapshots where the snapshot and stamp live, over an already tenant-scoped store
     * @param first the first request of the catalogue download
     * @param reader yields the reduced catalogue to persist, bounded by {@link FeedPolicy#maxSnapshotBytes()}
     */
    public Answer<FeedSnapshots.Refresh> refresh(FeedSnapshots snapshots,
                                               FeedRequest first,
                                               Supplier<? extends Reader<byte[]>> reader) throws FeedException {
        Objects.requireNonNull(snapshots, "snapshots");
        if (!configured()) {
            return Answer.skipped(unconfigured);
        }
        Answer<byte[]> drawn;
        try {
            drawn = fetch(first, reader);
        } catch (FeedException e) {
            throw deferred(snapshots, e);
        }
        if (drawn.status() == Status.DEGRADED) {
            // fetch() already logged the degrade; all that is left is to push the next attempt out.
            return Answer.degraded(deferred(snapshots, drawn.failure().orElseThrow()));
        }
        byte[] snapshot = drawn.value().orElseThrow();
        if (snapshot.length > policy.maxSnapshotBytes()) {
            FeedException oversized = deferred(snapshots, failure(FeedException.Reason.SNAPSHOT_CAP, 0, 1,
                    "the drawn snapshot of " + snapshot.length + " bytes exceeds the " + policy.maxSnapshotBytes()
                            + "-byte cap and was not committed; the previous snapshot keeps serving", null));
            if (policy.failMode() == FeedPolicy.FailMode.CLOSED) {
                throw oversized;
            }
            LOGGER.warn("The {} feed degraded: {}", feed, oversized.getMessage());
            return Answer.degraded(oversized);
        }
        try {
            return Answer.fetched(snapshots.commit(snapshot, policy.refreshInterval()));
        } catch (IOException e) {
            // The store itself is failing, so deferring through it is pointless.
            FeedException failure = failure(FeedException.Reason.TRANSPORT, 0, 1,
                    "the drawn snapshot could not be committed to the store", e);
            if (policy.failMode() == FeedPolicy.FailMode.CLOSED) {
                throw failure;
            }
            LOGGER.warn("The {} feed degraded: {}", feed, failure.getMessage());
            return Answer.degraded(failure);
        }
    }

    /** Push the next attempt out after a failed refresh, keeping the prior-good snapshot and its stamp intact. */
    private FeedException deferred(FeedSnapshots snapshots, FeedException failure) {
        try {
            snapshots.defer(policy.retryInterval());
        } catch (IOException e) {
            failure.addSuppressed(e);
        }
        return failure;
    }

    /** One complete attempt: every page of the answer, or a named failure - never something in between. */
    private <T> T draw(FeedRequest first, Reader<T> reader, Instant deadline, int attempt) throws FeedException {
        if (reader == null) {
            throw failure(FeedException.Reason.MALFORMED, 0, attempt,
                    "the feed supplied a null reader; null is never a legal reader", null);
        }
        FeedRequest request = first;
        for (int page = 1; ; page++) {
            if (page > policy.maxPages()) {
                // A feed that keeps advertising pages is refused rather than answered with the first maxPages, which
                // would look complete.
                throw failure(FeedException.Reason.PAGE_CAP, 0, attempt,
                        "the feed kept paginating past the " + policy.maxPages() + "-page cap at " + request.uri()
                                + "; refusing to serve a bounded-but-incomplete answer", null);
            }
            Duration left = Duration.between(clock.instant(), deadline);
            if (left.isNegative() || left.isZero()) {
                throw failure(FeedException.Reason.DEADLINE, 0, attempt,
                        "the fetch ran past its " + policy.deadline() + " deadline after " + (page - 1)
                                + " page(s)", null);
            }
            Duration timeout = left.compareTo(policy.requestTimeout()) < 0 ? left : policy.requestTimeout();
            Optional<FeedRequest> next = page(request, reader, timeout, page, attempt);
            if (next.isEmpty()) {
                try {
                    T value = reader.complete();
                    if (value == null) {
                        throw failure(FeedException.Reason.MALFORMED, 0, attempt,
                                "the feed's reader completed with null; null is never a legal answer", null);
                    }
                    return value;
                } catch (FeedException e) {
                    throw e;
                } catch (IOException | RuntimeException e) {
                    throw failure(FeedException.Reason.MALFORMED, 0, attempt,
                            "the feed's reader could not complete its answer", e);
                }
            }
            FeedRequest cursor = next.get();
            if (policy.sameOriginOnly() && !first.sameOrigin(cursor.uri())) {
                // A cursor on another origin would steer the fetch and carry this request's credential to it; no
                // legitimate cursor leaves its origin.
                throw failure(FeedException.Reason.CROSS_ORIGIN, 0, attempt,
                        "the feed's pagination cursor left its origin: " + cursor.uri() + " is not on "
                                + first.uri().getScheme() + "://" + first.uri().getHost(), null);
            }
            request = cursor;
        }
    }

    /** One request/response round: send, screen the status, cap the body, and let the reader fold it. */
    private <T> Optional<FeedRequest> page(FeedRequest request,
                                           Reader<T> reader,
                                           Duration timeout,
                                           int page,
                                           int attempt) throws FeedException {
        FeedResponse response;
        try {
            response = transport.send(request, timeout);
        } catch (FeedException e) {
            throw e;
        } catch (IOException e) {
            // A read timeout is an InterruptedIOException nobody interrupted, so the thread's flag decides: a
            // cancellation ends the fetch, a timeout is retryable.
            if (e instanceof InterruptedIOException && Thread.currentThread().isInterrupted()) {
                throw failure(FeedException.Reason.INTERRUPTED, 0, attempt, "interrupted while requesting "
                        + request.uri(), e);
            }
            throw failure(FeedException.Reason.TRANSPORT, 0, attempt,
                    "page " + page + " could not be fetched from " + request.uri(), e);
        }
        if (response == null) {
            throw failure(FeedException.Reason.MALFORMED, 0, attempt,
                    "the transport answered null for " + request.uri(), null);
        }
        try (FeedResponse open = response) {
            if (open.status() != 200) {
                // An error document is not an empty answer: parsing a 403 body would read as "no advisories".
                throw new FeedException(feed, FeedException.Reason.STATUS, open.status(), attempt,
                        "page " + page + " of " + request.uri() + " answered HTTP " + open.status()
                                + " - a rate limit, a rejected credential or an error, never silently an empty"
                                + " answer", null, open.retryAfter().orElse(null));
            }
            Optional<FeedRequest> next = reader.read(page, open.over(new Capped(open.body(), attempt)));
            if (next == null) {
                throw failure(FeedException.Reason.MALFORMED, 0, attempt,
                        "the feed's reader returned null instead of an Optional cursor", null);
            }
            return next;
        } catch (FeedException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw failure(FeedException.Reason.MALFORMED, 0, attempt,
                    "page " + page + " of " + request.uri() + " could not be read", e);
        }
    }

    private FeedException failure(FeedException.Reason reason,
                                  int status,
                                  int attempt,
                                  String detail,
                                  Throwable cause) {
        return new FeedException(feed, reason, status, attempt, detail, cause);
    }

    /** Apply the fail mode to a failure that survived every attempt. */
    private <T> Answer<T> failed(FeedException failure) throws FeedException {
        FeedException raised = failure == null
                ? failure(FeedException.Reason.TRANSPORT, 0, policy.maxAttempts(),
                        "the fetch made no attempt at all", null)
                : failure;
        if (policy.failMode() == FeedPolicy.FailMode.CLOSED) {
            throw raised;
        }
        LOGGER.warn("The {} feed degraded: {}", feed, raised.getMessage(), raised);
        return Answer.degraded(raised);
    }

    /** Folds one feed answer, page by page. A fresh instance is built per attempt, so it may hold mutable accumulation
     *  without leaking a partial answer: {@link #complete()} is reached only when every page has been read. A feed
     *  answering one response takes {@link #document} instead. */
    public interface Reader<T> {

        /**
         * The reader for a feed whose whole answer is one response: parse the body, and there is no next page. It
         * answers a {@link Supplier} to keep the fresh-accumulator-per-attempt rule.
         *
         * {@snippet :
         * FeedClient.Answer<JsonNode> answer = client.fetch(FeedRequest.get(uri), FeedClient.Reader.document(JSON::readTree));
         * }
         *
         * @param parse turns the response body into the answer, reading from the capped stream - a streaming parse of
         *     one document, or a fold of a newline-delimited one - and not retaining it, since the response closes when
         *     the parse returns. There is no whole-body form: a catalogue is megabytes, which the byte cap exists to
         *     keep off the heap.
         */
        static <T> Supplier<Reader<T>> document(Body<T> parse) {
            Objects.requireNonNull(parse, "parse");
            return () -> new Reader<T>() {

                private T value;

                @Override
                public Optional<FeedRequest> read(int page, FeedResponse response) throws IOException {
                    value = parse.read(response.body());
                    return Optional.empty();        // one response is the whole answer; there is no cursor
                }

                @Override
                public T complete() {
                    return value;                   // null is refused by the client, which is where that rule lives
                }
            };
        }

        /** How one response body becomes an answer: the vendor-specific half of {@link #document}. */
        @FunctionalInterface
        interface Body<T> {

            /** Parse {@code body}, already capped at {@link FeedPolicy#maxResponseBytes()}, into the answer. Read from
             *  the stream; do not close or retain it. */
            T read(InputStream body) throws IOException;
        }

        /**
         * Fold one page and say where the next one is.
         *
         * @param page the 1-based page index within this attempt
         * @param response the answer, its body capped and closed as soon as this method returns
         * @return the request drawing the next page, or empty when this was the last; it stays on the first request's
         *     origin unless the policy says otherwise
         */
        Optional<FeedRequest> read(int page, FeedResponse response) throws IOException;

        /** The finished answer, called exactly once and only after every page has been folded. */
        T complete() throws IOException;
    }

    /** How a fetch ended. */
    public enum Status {
        /** Every page was drawn and the reader completed: the answer carries a value. */
        FETCHED,
        /** The feed is not configured: nothing was fetched, nothing was stored, and nothing failed. */
        SKIPPED,
        /** The fetch failed under a fail-soft policy: the answer carries the failure, and no value. */
        DEGRADED
    }

    /** What one fetch produced: a value exactly when {@link Status#FETCHED}, a failure exactly when
     *  {@link Status#DEGRADED}, so an empty answer and a failed one can never be confused - an advisory feed must not
     *  report an outage as clean. */
    public record Answer<T>(Status status, Optional<T> value, Optional<FeedException> failure, String note) {

        public Answer {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(failure, "failure");
            Objects.requireNonNull(note, "note");
            if (value.isPresent() != (status == Status.FETCHED)) {
                throw new IllegalArgumentException("A fetched answer carries a value and no other answer does: "
                        + status);
            }
            if (failure.isPresent() != (status == Status.DEGRADED)) {
                throw new IllegalArgumentException("A degraded answer carries a failure and no other answer does: "
                        + status);
            }
        }

        static <T> Answer<T> fetched(T value) {
            return new Answer<>(Status.FETCHED, Optional.of(value), Optional.empty(), "");
        }

        static <T> Answer<T> skipped(String note) {
            return new Answer<>(Status.SKIPPED, Optional.empty(), Optional.empty(), note);
        }

        static <T> Answer<T> degraded(FeedException failure) {
            return new Answer<>(Status.DEGRADED, Optional.empty(), Optional.of(failure), failure.getMessage());
        }

        /** Whether this answer carries a value. */
        public boolean fetched() {
            return status == Status.FETCHED;
        }

        /** Whether the feed was skipped because it is not configured. */
        public boolean skipped() {
            return status == Status.SKIPPED;
        }

        /** The value, or {@code fallback} when this fetch was skipped or degraded. */
        public T orElse(T fallback) {
            return value.orElse(fallback);
        }
    }

    /** How the client waits between retries, injected so a test asserts the backoff schedule without spending it. */
    @FunctionalInterface
    public interface Pause {

        void pause(Duration delay) throws InterruptedException;

        /** The production form: sleep, honouring an interrupt. */
        static Pause sleeping() {
            return delay -> {
                if (!delay.isZero() && !delay.isNegative()) {
                    Thread.sleep(delay);
                }
            };
        }
    }

    /** A response body that fails visibly past the policy's byte cap instead of being read unbounded. */
    private final class Capped extends FilterInputStream {

        private final int attempt;
        private long read;

        private Capped(InputStream in, int attempt) {
            super(in);
            this.attempt = attempt;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int value = super.read(buffer, offset, length);
            if (value > 0) {
                count(value);
            }
            return value;
        }

        @Override
        public long skip(long requested) throws IOException {
            // Skipped bytes were still sent by the feed, so they count.
            long skipped = super.skip(requested);
            if (skipped > 0) {
                count(skipped);
            }
            return skipped;
        }

        private void count(long bytes) throws IOException {
            read += bytes;
            if (read > policy.maxResponseBytes()) {
                throw failure(FeedException.Reason.RESPONSE_CAP, 0, attempt,
                        "a response body exceeded the " + policy.maxResponseBytes() + "-byte cap", null);
            }
        }
    }
}
