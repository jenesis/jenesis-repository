package build.jenesis.repository.server;

import module java.base;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import io.micrometer.observation.ObservationRegistry;
import build.jenesis.repository.store.SingleFlight;
import io.micrometer.observation.Observation;

/**
 * The format-agnostic pull-through loop shared by every dispatcher. A {@code GET} or {@code HEAD} of a path the
 * format handles is served locally first through a {@code Deferred} exchange that defers the response until it sees
 * the format's status; if that is a 404 the format's {@link ProxyFormat#proxy} adapter is given control to fetch from
 * upstream, cache and serve - so a later read is a local hit. A request with any other method, a local hit, or an
 * adapter that declines passes straight through (the 404 stands). The single network call sits behind
 * {@link ProxyFormat.Fetcher} so the cache behaviour is tested without the network.
 *
 * <p><b>Concurrent readers of one uncached artifact make one upstream request, not one each.</b> Without that, a
 * fleet resolving a release the moment it lands turns into as many upstream fetches as it has jobs - and the limit
 * that bites is the upstream's, which is applied <em>per address</em>. One node is one address, so coalescing
 * within this JVM removes exactly the amplification that gets a deployment throttled or blocked; coordinating
 * across nodes would add a distributed lock to a read path to solve a problem no upstream is measuring.
 *
 * <p>The fill is what is shared, not the response. Bytes stream rather than buffer, so one download cannot be
 * handed to several readers; instead the first reader fills the cache while the others wait and then retry the
 * local-first path, which is an ordinary hit by then. A reader whose wait ends without a servable local answer -
 * the leader failed, timed out, or the artifact is genuinely absent upstream - fetches for itself, so this is an
 * optimisation that never changes an outcome.
 *
 * <p>Each proxy-eligible read is wrapped in a {@code jenreg.proxy.fetch} {@link Observations observation} tagged
 * with the {@code format} and the {@code outcome} - {@code hit} (served locally, no upstream call), {@code miss}
 * (fetched from upstream) or {@code negative} (upstream also missed) - so the upstream leg is visible in metrics,
 * logs and traces from one instrumentation point. A leg that asked the upstream also carries what the upstream
 * <em>answered</em> as {@code upstream}: the status of the last request the fetcher made ({@code 200},
 * {@code 404}, {@code 503}), {@code unreachable} where the transport got no answer, and {@code unasked} where the
 * format's leg declined the path before any request was sent. A {@code negative} alone was a verdict without its
 * observation: the proxy answered {@code 404} for a path rubygems.org was serving {@code 200} at that moment, and
 * the metric could only say the fetch had failed - an hour of reproduction to learn that the format had never asked
 * (it does not serve the legacy {@code specs.4.8.gz} index), which this tag now says in the log line. Given an {@link ObservationRegistry#NOOP NOOP} registry (the
 * default constructor, and every test that builds this directly) the wrapper is inert.
 */
public final class PullThroughCache {

    private final ProxyFormat.Fetcher fetcher;
    /**
     * Fills in flight on this node, keyed by upstream and path. Entries are removed in a {@code finally}, so the
     * map is bounded by the number of requests concurrently missing; the cap below is the belt to that braces, and
     * a reader arriving past it simply fetches for itself rather than queueing behind an unbounded structure.
     */
    private static final SingleFlight<String, Void> FILLING = new SingleFlight<>();

    private static final int MAX_FILLING = 1024;

    /** How long a waiting reader gives the leader before deciding to fetch for itself. */
    private static final Duration FOLLOW = Duration.ofMinutes(5);

    private final ObservationRegistry observations;
    private final PullThroughHooks hooks;

    public PullThroughCache(ProxyFormat.Fetcher fetcher) {
        this(fetcher, ObservationRegistry.NOOP);
    }

    public PullThroughCache(ProxyFormat.Fetcher fetcher, PullThroughHooks hooks) {
        this(fetcher, ObservationRegistry.NOOP, hooks);
    }

    public PullThroughCache(ProxyFormat.Fetcher fetcher, ObservationRegistry observations) {
        this(fetcher, observations, PullThroughHooks.NONE);
    }

    /**
     * Bind an edition's {@link PullThroughHooks} into the loop. The other constructors delegate here with
     * {@link PullThroughHooks#NONE} (the {@link EdgeHooks} convenience-constructor idiom), so an existing call site is
     * unchanged and serves byte-for-byte as before.
     */
    public PullThroughCache(ProxyFormat.Fetcher fetcher, ObservationRegistry observations, PullThroughHooks hooks) {
        this.fetcher = fetcher;
        this.observations = observations;
        this.hooks = hooks;
    }

    public void serve(RepositoryFormat format,
                      ProxyFormat proxy,
                      URI upstream,
                      FormatExchange exchange,
                      ArtifactStore store) throws IOException {
        if (!exchange.method().equals("GET") && !exchange.method().equals("HEAD")) {
            format.handle(exchange, store);
            return;
        }
        Observations.observe(observations, "jenreg.proxy.fetch", null, null, observation -> {
            observation.lowCardinalityKeyValue("format", format.name());
            // Present on every outcome, because a meter's tag keys must not vary with its value: a hit never asks
            // the upstream, and says so, rather than carrying one key fewer than a miss.
            observation.lowCardinalityKeyValue("upstream", "unasked");
            // Consult the edition BEFORE the local-first serve, so a cached hit is verified against the current gate
            // before any byte is written. The free NONE hook returns serveThrough with no store read, so the hit path
            // below is byte-for-byte as before; the decision is made ahead of serving, never by wrapping the stream.
            PullThroughHooks.HitDecision decision = hooks.verifyHit(format, exchange.path(), store);
            if (decision instanceof PullThroughHooks.HitDecision.Withhold) {
                // A now-retracted/rejected artifact the current gate refuses: 404 without serving the local bytes and
                // without a miss-leg re-fetch (the caveat's "false must not dump to the miss leg").
                observation.lowCardinalityKeyValue("outcome", "withheld");
                exchange.respond(404);
                return null;
            }
            if (decision instanceof PullThroughHooks.HitDecision.ServeLocal serveLocal) {
                // The edition serves the local bytes itself, fail-closed over the local blob (no upstream fetch).
                observation.lowCardinalityKeyValue("outcome", "verified");
                serveLocal.serve().serve(format, exchange, store);
                return null;
            }
            // serveThrough (the free default): the local-first serve runs exactly as today.
            Deferred deferred = new Deferred(exchange);
            format.handle(deferred, store);
            if (!deferred.missed()) {
                observation.lowCardinalityKeyValue("outcome", "hit");
                return null;
            }
            String filling = upstream + "\u0000" + exchange.path();
            // Past the cap a reader fetches for itself rather than queue behind an unbounded structure.
            SingleFlight.Outcome<Void> outcome = FILLING.inFlight() >= MAX_FILLING
                    ? new SingleFlight.Overdue<>()
                    : FILLING.run(filling, () -> {
                        fetch(exchange, store, format, proxy, upstream, observation);
                        return null;
                    }, FOLLOW);
            if (outcome instanceof SingleFlight.Led<Void>) {
                return null;                                    // this reader was the one that fetched
            }
            if (!(outcome instanceof SingleFlight.Overdue<Void>)) {
                // The leader has finished, however it finished - a waiting reader does not inherit a failure it can
                // do nothing with. The local-first path is tried once more and is normally a hit now. This is a
                // second attempt at the SAME exchange, which is safe because Deferred withholds the response until
                // it has seen the format's status - nothing was written for the first miss.
                Deferred filled = new Deferred(exchange);
                format.handle(filled, store);
                if (!filled.missed()) {
                    observation.lowCardinalityKeyValue("outcome", "coalesced");
                    return null;
                }
                // The leader filled nothing this reader can serve, so it fetches for itself.
            }
            fetch(exchange, store, format, proxy, upstream, observation);
            return null;
        });
    }

    /** Fetch the missed path from the upstream through the format, screened by the hooks, and record the outcome -
     *  and, beside it, what the upstream answered, so a {@code negative} is a reading rather than a prompt to
     *  reproduce. */
    private void fetch(FormatExchange exchange, ArtifactStore store, RepositoryFormat format, ProxyFormat proxy,
                       URI upstream, Observation observation) throws IOException {
        Answered answered = new Answered(hooks.screenFetch(exchange.path(), fetcher, store));
        boolean served;
        try {
            served = proxy.proxy(exchange, store, upstream, answered);
        } finally {
            observation.lowCardinalityKeyValue("upstream", answered.last());
        }
        if (served) {
            observation.lowCardinalityKeyValue("outcome", "miss");
            observePublish(format, exchange.path(), store);
        } else {
            observation.lowCardinalityKeyValue("outcome", "negative");
            exchange.respond(404);
        }
    }

    /**
     * The fetcher a leg is handed, remembering what the upstream answered to the last request made through it: the
     * status, {@code unreachable} for the transport's empty answer, and {@code unasked} until a leg asks at all. A
     * decorator over all three legs, kept whole rather than derived (see {@link ProxyFormat.Fetcher.Buffered} for why
     * a decorator that inherits a derivation collapses the streaming path).
     */
    private static final class Answered implements ProxyFormat.Fetcher {

        private final ProxyFormat.Fetcher delegate;
        private volatile String last = "unasked";

        private Answered(ProxyFormat.Fetcher delegate) {
            this.delegate = delegate;
        }

        String last() {
            return last;
        }

        @Override
        public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) throws IOException {
            Optional<ProxyFormat.Fetched> fetched = delegate.fetch(url, requestHeaders);
            last = fetched.map(response -> Integer.toString(response.status())).orElse("unreachable");
            return fetched;
        }

        @Override
        public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders)
                throws IOException {
            Optional<ProxyFormat.Download> download = delegate.download(url, requestHeaders);
            last = download.map(response -> Integer.toString(response.status())).orElse("unreachable");
            return download;
        }

        @Override
        public Optional<ProxyFormat.Head> head(URI url, Map<String, String> requestHeaders) throws IOException {
            Optional<ProxyFormat.Head> head = delegate.head(url, requestHeaders);
            last = head.map(response -> Integer.toString(response.status())).orElse("unreachable");
            return head;
        }
    }

    /**
     * Fire the after-commit {@link build.jenesis.repository.store.PublicationObserver}s once a proxy leg has fetched,
     * stored and served an upstream miss, so a proxy-publish is observed exactly like a direct publish. Today the event
     * rides the format's embedded publish on the proxy path; as that embedded publish is retired the observer event
     * would otherwise be lost, so it is fired here at the point the fetched body is committed to the store. Best-effort
     * and contained: it fires only when the artifact is actually published ({@link Publication#located located} - so a
     * quarantined or rejected proxy leg is not observed) and any failure building the event is swallowed, never failing
     * the serve.
     */
    private static void observePublish(RepositoryFormat format, String path, ArtifactStore store) {
        try {
            Publication publication = new Publication(store);
            Optional<String> key = publication.located(path);
            if (key.isEmpty()) {
                return;
            }
            String hash = key.get().substring("blobs/".length());
            publication.published(descriptor(format, path, store).withBlob(hash, store.size(key.get())));
        } catch (Exception _) {
            // best-effort observer parity; a proxy serve must never fail because an observer event could not be built
        }
    }

    /** The claiming format's layout descriptor for the path when it has one, else a bare format-name-and-path
     *  descriptor - the neutral identity the observer keys on. */
    private static ArtifactDescriptor descriptor(RepositoryFormat format, String path, ArtifactStore store) {
        if (format instanceof ArtifactLayout layout) {
            // The repository-scoped overload - see the same call in ScreenedDispatch: a per-repository layout has no
            // answer without it, and a proxied artifact would be observed coordinate-less.
            Optional<ArtifactDescriptor> described = layout.describe(path, store);
            if (described.isPresent()) {
                return described.get();
            }
        }
        return ArtifactDescriptor.at(format.name(), path);
    }

    /**
     * A {@link FormatExchange} that defers committing to the real exchange until it sees the format's status, so a
     * local hit streams its body straight to the client with nothing buffered, while a local {@code 404} is swallowed
     * (its tiny body discarded) and reported through {@link #missed()} so the loop can hand control to the proxy
     * adapter, which writes the real response itself. This works because a format always sets its status (and any
     * response headers) before it writes the body. Response headers are held until the commit; reads delegate to the
     * real exchange unchanged.
     */
    /** Waits for the leader's fill; false when the wait ended without one, so the caller fetches for itself. */
    private static final class Deferred implements FormatExchange {

        private final FormatExchange delegate;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private boolean missed;

        private Deferred(FormatExchange delegate) {
            this.delegate = delegate;
        }

        @Override
        public String method() {
            return delegate.method();
        }

        @Override
        public String path() {
            return delegate.path();
        }

        @Override
        public String requestUri() {
            return delegate.requestUri();
        }

        @Override
        public String scheme() {
            return delegate.scheme();
        }

        @Override
        public String remoteAddress() {
            return delegate.remoteAddress();
        }

        @Override
        public String queryParameter(String name) {
            return delegate.queryParameter(name);
        }

        @Override
        public String requestHeader(String name) {
            return delegate.requestHeader(name);
        }

        @Override
        public String setting(String key) {
            return delegate.setting(key);
        }

        @Override
        public InputStream requestStream() throws IOException {
            return delegate.requestStream();
        }

        @Override
        public void setResponseHeader(String name, String value) {
            headers.put(name, value);
        }

        @Override
        public OutputStream respond(int status, long contentLength) throws IOException {
            if (status == 404) {
                missed = true;
                return OutputStream.nullOutputStream();
            }
            headers.forEach(delegate::setResponseHeader);
            return delegate.respond(status, contentLength);
        }

        /**
         * A buffered answer is handed to the delegate whole, rather than left to the interface default that would
         * stream it through {@link #respond(int, long)}.
         *
         * <p>The default is what a wrapper silently inherits, and it costs the response its conditional
         * revalidation: only the servlet exchange's own buffered override computes the {@code ETag} and answers a
         * matching {@code If-None-Match} with {@code 304}. Every generated index a format serves - a packument, a
         * {@code maven-metadata.xml}, a PyPI index - travels this way, so in a proxy-capable repository each of them
         * was re-downloaded in full on every resolve while the same index in a hosted-only repository revalidated.
         * The 404 probe above still has to see the miss, which is why only the buffered path is forwarded here.
         */
        @Override
        public void respond(int status, byte[] content) throws IOException {
            if (status == 404) {
                missed = true;
                return;
            }
            headers.forEach(delegate::setResponseHeader);
            delegate.respond(status, content);
        }

        private boolean missed() {
            return missed;
        }
    }
}
