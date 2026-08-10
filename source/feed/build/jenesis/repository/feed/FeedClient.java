package build.jenesis.repository.feed;

import module java.base;
import module org.slf4j;

/**
 * The ONE bounded client every externally-sourced HTTP JSON feed fetches through - a vulnerability advisory API, a
 * known-exploited catalogue, an exploit-probability model, a maintainer-health dataset. It owns exactly the concerns
 * that are identical for every vendor and where the recurring audit defects lived: timeouts and the whole-fetch
 * deadline, header (authentication) injection, the non-200 branch, cursor pagination bounded by a page cap that
 * <em>fails visibly</em>, a response byte cap, a retry schedule, the fail-closed / fail-soft policy, the clean
 * self-skip when a feed is not configured, and - through {@link FeedSnapshots} - a mirrored catalogue committed
 * together with its staleness stamp.
 *
 * <p>It owns none of the vendor-specific half. The URL shape, the credential, the wire format, the field mapping and
 * the ecosystem/coordinate mapping stay in the feed module, which reaches this client through the
 * {@link FeedRequest}s it builds and the {@link Reader} it hands in to fold each page.
 *
 * <p><strong>Nothing global is discovered.</strong> The transport, the clock, the pause and (for a snapshot feed) the
 * already tenant-scoped store all arrive as arguments. A whole feed can therefore be driven from recorded responses
 * with a transport that throws on any real socket, and a read path can be asserted to make no request at all.
 *
 * <h3>Fetching, and why a partial answer cannot escape</h3>
 * A caller supplies a {@link Supplier} of {@link Reader}, not a reader: one fresh accumulator is built per
 * <em>attempt</em>, and {@link Reader#complete()} is called only once every page of that attempt has been drawn.
 * A fetch that hits a cap, a bad status, the deadline or the attempt limit therefore drops its half-filled
 * accumulator on the floor - there is no code path on which a caller receives a value assembled from some of the
 * pages. That is the "bounds fail visibly" gate made structural rather than remembered, and it is also what makes a
 * retry safe: an attempt never resumes another attempt's partial state.
 *
 * {@snippet :
 * FeedClient client = FeedClient.of("kev", FeedTransport.jdk(Duration.ofSeconds(10)), FeedPolicy.closed());
 * FeedClient.Answer<List<Advisory>> answer = client.fetch(FeedRequest.get(uri).bearer(token), MyReader::new);
 * }
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> A client is immutable and safe to share across threads; it holds no per-fetch state. It
 *     is as thread-safe as the {@link FeedTransport} handed in. It does <em>not</em> serialise concurrent fetches:
 *     a feed that must collapse a burst into one upstream call (a metered vendor) does that in front of the client,
 *     and a scheduled mirror refresh is already single-flight.</li>
 * <li><b>Idempotency / replay.</b> A fetch is a read of the vendor and mutates nothing outside the store, so it may
 *     be repeated freely. A retry restarts the whole fetch from its first request with a <em>fresh</em> reader, so
 *     no page is ever folded twice into one answer. {@link #refresh} is idempotent against the store: an unchanged
 *     catalogue commits the same content-addressed body and only advances the stamp.</li>
 * <li><b>Absence sentinel.</b> Every call answers an {@link Answer}; {@code null} is never returned and a
 *     {@code null} from a {@link Reader} fails loudly. An unconfigured client answers {@link Status#SKIPPED} with
 *     the reason, a degraded fetch {@link Status#DEGRADED} with the failure, and only {@link Status#FETCHED} carries
 *     a value - the record makes the other combinations unrepresentable.</li>
 * <li><b>Selection failure (&sect;9).</b> A feed whose required configuration is unset builds an
 *     {@link #unconfigured} client and self-skips cleanly, touching neither the network nor the store. It never
 *     degrades into "a feed that answers nothing", which a consumer would read as a clean result.</li>
 * <li><b>Streaming (&sect;1).</b> A response body reaches a {@link Reader} as an {@link java.io.InputStream}, capped
 *     at {@link FeedPolicy#maxResponseBytes()}, so a multi-megabyte catalogue is parsed incrementally and is never
 *     materialised. Only the reduced snapshot a {@link Reader} of {@code byte[]} yields is held whole, and that is
 *     bounded by {@link FeedPolicy#maxSnapshotBytes()}. {@link Reader#document} is the shared reader for the
 *     single-response feeds, and it hands that same stream to the parse for the same reason: <b>there is no
 *     whole-body reader here, and its absence is deliberate</b>. A convenience answering the body as a
 *     {@code String} or a {@code byte[]} would be the one every feed reached for, and every feed would then spend
 *     heap proportional to what the vendor chose to send, inside the very client that exists to bound it.</li>
 * <li><b>Tenant scoping (&sect;6).</b> The client stores nothing by itself. {@link #refresh} writes only through the
 *     {@link FeedSnapshots} it is handed, whose store is already tenant-scoped; the client never scopes or discovers
 *     a store.</li>
 * <li><b>Error visibility (&sect;9).</b> Nothing is swallowed. Under {@link FeedPolicy.FailMode#CLOSED} every failure
 *     is thrown as a {@link FeedException} naming the feed, the reason, the status and the attempts. Under
 *     {@link FeedPolicy.FailMode#SOFT} it is returned as {@link Status#DEGRADED} carrying that same exception and
 *     logged once - the blast radius being a ranking aid that is absent for this cycle, never an advisory answer
 *     that reads as clean.</li>
 * <li><b>Read purity (&sect;10).</b> This client is the <em>write</em> half of a feed: fetching is what a refresh
 *     does. A read path renders {@link FeedSnapshots#current()} and {@link FeedSnapshots#open}, which reach no
 *     network at all - proven by handing a query path a transport that throws on any call.</li>
 * <li><b>Staleness (&sect;10).</b> A mirrored feed's staleness is durable, not process-local: {@link #refresh}
 *     commits the fetch instant in the same store object as the snapshot it stamps, so a restart, a failover and a
 *     second replica all see the same answer to "when was this last refreshed", and a view can always show it.</li>
 * <li><b>Lifecycle / ownership.</b> The client owns nothing it did not create: the transport, its HTTP client, the
 *     clock and the store belong to the caller, which also closes them. It starts no thread and caches nothing
 *     between calls, so an instance may be built per feed at wiring time and kept, or built per call.</li>
 * <li><b>Ordering / concurrency.</b> Pages are drawn strictly in cursor order on the calling thread, one request at
 *     a time - there is no fan-out and no parallelism to make results order-dependent. Two concurrent
 *     {@link #refresh} calls are arbitrated by the snapshot pointer's compare-and-set, and the loser adopts the
 *     winner's stamp.</li>
 * <li><b>Bounded work / cancellation (&sect;12).</b> Every dimension is capped and each cap has a <em>named</em>
 *     outcome: {@link FeedPolicy#maxPages()} pages ({@link FeedException.Reason#PAGE_CAP}),
 *     {@link FeedPolicy#maxResponseBytes()} per body ({@link FeedException.Reason#RESPONSE_CAP}),
 *     {@link FeedPolicy#maxSnapshotBytes()} per committed snapshot
 *     ({@link FeedException.Reason#SNAPSHOT_CAP}), {@link FeedPolicy#maxAttempts()} attempts,
 *     {@link FeedPolicy#requestTimeout()} per request and {@link FeedPolicy#deadline()} for the whole fetch across
 *     pages and retries ({@link FeedException.Reason#DEADLINE}). No cap returns a plausible-but-incomplete answer:
 *     reaching one always fails, and under {@link FeedPolicy.FailMode#SOFT} the prior-good snapshot keeps serving.
 *     An interrupt is honoured promptly, restores the thread's interrupt flag and ends the fetch as
 *     {@link FeedException.Reason#INTERRUPTED}.</li>
 * <li><b>Durability / delivery (&sect;13).</b> {@link #fetch} is durable-free: it writes nothing. {@link #refresh}'s
 *     commit point is the snapshot pointer's compare-and-set, pointer-last after the body is durable, so the
 *     visible states are exactly "the previous snapshot" and "the new snapshot" - never a mixture. A refresh that
 *     does not complete leaves the prior-good snapshot and its fetch instant untouched and only moves
 *     {@code nextRefreshAt} out by {@link FeedPolicy#retryInterval()}. The durable source of truth is that pointer;
 *     a lost refresh heals by being retried on schedule.</li>
 * </ol>
 */
public final class FeedClient {

    private static final Logger LOG = LoggerFactory.getLogger(FeedClient.class);

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

    /** A client over the system clock, sleeping between retries - the production form. */
    public static FeedClient of(String feed, FeedTransport transport, FeedPolicy policy) {
        return of(feed, transport, policy, Clock.systemUTC(), Pause.sleeping());
    }

    /**
     * A client with the clock and the retry pause injected - the form a test drives, so a backoff schedule and a
     * deadline are asserted exactly and instantly rather than waited out.
     */
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
     * The clean self-skip: a client for a feed whose configuration is incomplete. Every call answers
     * {@link Status#SKIPPED} naming what is missing, and no call touches the network or the store - so an
     * unconfigured licensed feed costs nothing and, crucially, is never mistaken for a feed that answered nothing.
     *
     * @param missing the configuration keys that are unset, named in the skip reason so an operator is told what to
     *                supply rather than left with a silently inert feed.
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
     * Draw a feed answer: send {@code first}, hand each response to a fresh {@link Reader}, follow the cursor the
     * reader returns until it returns none, and answer with what {@link Reader#complete()} then yields.
     *
     * @param first  the first request - the vendor URL with the vendor's credential already on it.
     * @param reader builds a fresh accumulator per attempt (see the class javadoc: this is what makes a partial
     *               answer unrepresentable and a retry safe).
     * @return {@link Status#FETCHED} with the value, {@link Status#SKIPPED} when this client is unconfigured, or
     *         {@link Status#DEGRADED} when the policy fails soft.
     * @throws FeedException when the policy fails closed and the fetch did not complete.
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
     * Refresh a mirrored catalogue: fetch it exactly as {@link #fetch} does, then commit the snapshot the reader
     * yielded <em>together with</em> its staleness stamp through {@code snapshots}.
     *
     * <p>What happens at each outcome is the whole point of the method:
     * <ul>
     * <li><b>Complete fetch</b> - the body is written, the pointer moves by compare-and-set, and the answer carries
     *     the new {@link FeedSnapshots.Stamp}, whose fetch instant and snapshot were committed by that one write.</li>
     * <li><b>Any incomplete fetch</b> (a cap, a bad status, the deadline, an exhausted retry budget) - <em>nothing</em>
     *     is committed. The prior-good snapshot and its fetch instant stand untouched; only {@code nextRefreshAt} is
     *     pushed out by {@link FeedPolicy#retryInterval()} so the failure is retried rather than hammered. The failure
     *     is then thrown (fail-closed) or returned as {@link Status#DEGRADED} (fail-soft).</li>
     * <li><b>Unconfigured</b> - {@link Status#SKIPPED}; the store is not touched at all.</li>
     * </ul>
     *
     * @param snapshots where this feed's snapshot and stamp live, over an already tenant-scoped store.
     * @param first     the first request of the catalogue download.
     * @param reader    yields the reduced catalogue to persist; bounded by {@link FeedPolicy#maxSnapshotBytes()}.
     */
    public Answer<FeedSnapshots.Stamp> refresh(FeedSnapshots snapshots,
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
            LOG.warn("The {} feed degraded: {}", feed, oversized.getMessage());
            return Answer.degraded(oversized);
        }
        try {
            return Answer.fetched(snapshots.commit(snapshot, policy.refreshInterval()));
        } catch (IOException e) {
            // The store itself is failing, so there is no point deferring through it as well.
            FeedException failure = failure(FeedException.Reason.TRANSPORT, 0, 1,
                    "the drawn snapshot could not be committed to the store", e);
            if (policy.failMode() == FeedPolicy.FailMode.CLOSED) {
                throw failure;
            }
            LOG.warn("The {} feed degraded: {}", feed, failure.getMessage());
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
                // The gate-4 outcome: a feed that keeps advertising another page is refused outright rather than
                // answered with the first maxPages pages, which would be a plausible but incomplete view.
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
                // A vendor-supplied cursor pointing at another origin steers the fetch (an SSRF when the target is
                // internal) and carries this request's credential header to it. No legitimate cursor leaves its own
                // origin, so this fails closed and visibly.
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
            // A SocketTimeoutException is an InterruptedIOException that nobody interrupted, so the thread's flag -
            // not the exception's type - decides: a real cancellation ends the fetch, a read timeout is retryable.
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
        LOG.warn("The {} feed degraded: {}", feed, raised.getMessage(), raised);
        return Answer.degraded(raised);
    }

    /**
     * Folds one feed answer, page by page. A fresh instance is built per attempt, so an implementation may hold the
     * mutable accumulation it needs without ever leaking a partial answer: {@link #complete()} is reached only when
     * every page has been read.
     *
     * <p>A feed whose answer is <em>one</em> response - no cursor to follow - implements none of this and takes
     * {@link #document} instead; that is the majority shape, and it was six near-identical private classes before it
     * had a home here.
     */
    public interface Reader<T> {

        /**
         * The reader for a feed whose whole answer is a single response: parse the body, and there is no next page.
         *
         * <p>This is the shape a vendor query has when it answers one document - an envelope, a filtered index, a
         * resolution step, a batch of scores, a stream of records. Every such feed wrote the same twelve lines: hold
         * a field, parse into it in {@code read}, return {@code Optional.empty()}, hand the field back from
         * {@code complete}. The twelve lines are the client's to own; what stays the feed's is {@code parse}, which
         * is the only vendor-specific part of them.
         *
         * <p>It answers a {@link Supplier}, not a reader, because the client's fresh-accumulator-per-attempt rule is
         * the structural half of "bounds fail visibly" (see the class javadoc) - a shared reader that could be reused
         * across attempts would be a way to smuggle one attempt's state into the next.
         *
         * {@snippet :
         * FeedClient.Answer<JsonNode> answer = client.fetch(FeedRequest.get(uri), FeedClient.Reader.document(JSON::readTree));
         * }
         *
         * @param parse turns the response body into the answer. Deliberately given the {@link InputStream} and not
         *              the bytes: there is no whole-body convenience here and there will not be one, because a
         *              catalogue body is megabytes and materialising it would spend the heap the policy's byte cap
         *              exists to bound (&sect;1, and {@code StreamingPrincipleTest} catches a {@code readAllBytes}
         *              on this path). A parse reads <em>from</em> the stream - a streaming JSON parse of one
         *              document, or a record-per-line fold of a newline-delimited one - and must not retain it:
         *              the response is closed the moment the parse returns.
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

        /** How one response body becomes an answer - the vendor-specific half of {@link #document}, and the only
         *  half of it that is not the same for every feed. */
        @FunctionalInterface
        interface Body<T> {

            /**
             * Parse {@code body} - already capped at {@link FeedPolicy#maxResponseBytes()} by the client - into the
             * answer. Read from the stream rather than materialising it; do not close it and do not retain it beyond
             * this call.
             */
            T read(InputStream body) throws IOException;
        }

        /**
         * Fold one page and say where the next one is.
         *
         * @param page     the 1-based page index within this attempt.
         * @param response the answer, whose body stream is capped at the policy's response cap and is closed as soon
         *                 as this method returns - so a reader consumes what it needs here rather than retaining it.
         * @return the request drawing the next page, or empty when this was the last one. The cursor must stay on the
         *         first request's origin unless the policy says otherwise.
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

    /**
     * What one fetch produced. A value is present exactly when {@link Status#FETCHED} and a failure exactly when
     * {@link Status#DEGRADED} - enforced here, so "an empty answer" and "a failed answer" can never be confused by
     * a consumer, which is the whole reason an advisory feed must not report an outage as a clean package.
     */
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

    /**
     * How the client waits between retries, injected so a test asserts the exact backoff schedule without spending
     * it. The production form sleeps; a test's form records the durations it was asked for.
     */
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
            // Skipped bytes are spent bytes: a reader that jumps over a payload still made the feed send it.
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
