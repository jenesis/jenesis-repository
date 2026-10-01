package build.jenesis.repository.proxy;

import module java.base;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilitySource;

/**
 * A {@link ProxyFormat.Fetcher} decorator that revalidates a proxied mutable index instead of re-downloading it: it
 * keeps a fetched body with its {@code ETag} / {@code Last-Modified}, sends the next fetch of the URL conditionally,
 * serves the kept body on {@code 304} and refreshes on {@code 200}. The upstream is asked every time - only the
 * transfer is saved - so nothing stale is served and no freshness lifetime is needed. Only {@link #fetch} is
 * revalidated; an immutable artifact is downloaded once and served from the store, so {@code download} passes through.
 * Keyed by URL, bounded, concurrent-safe.
 *
 * <p>It is its own {@link ObservabilitySource}: {@code jenrepo.proxy.revalidation.bytes}, a bounded gauge against the
 * byte ceiling past which the oldest entries go, {@code jenrepo.proxy.revalidation.entries}, and a
 * {@code jenrepo.proxy.revalidation} health check. Eviction is lazy on the store path, so {@link #taskStatuses()} is
 * empty.
 */
public final class RevalidatingFetcher implements ProxyFormat.Fetcher, ObservabilitySource {

    private static final long MAX_TOTAL = 64L * 1024 * 1024;
    private static final int MAX_BODY = 8 * 1024 * 1024;

    private record Cached(byte[] body, Map<String, String> headers, String etag, String lastModified, long sequence) {
    }

    private final ProxyFormat.Fetcher delegate;
    private final ConcurrentMap<URI, Cached> cache = new ConcurrentHashMap<>();
    private final AtomicLong bytes = new AtomicLong();
    // A monotonic stamp per (re)fetch, so eviction is oldest-first: the map's iteration order says nothing about age.
    private final AtomicLong sequence = new AtomicLong();

    public RevalidatingFetcher(ProxyFormat.Fetcher delegate) {
        this.delegate = delegate;
    }

    @Override
    public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) throws IOException {
        Cached cached = cache.get(url);
        Optional<ProxyFormat.Fetched> fetched = delegate.fetch(url, conditional(requestHeaders, cached));
        if (fetched.isEmpty()) {
            return fetched;
        }
        ProxyFormat.Fetched response = fetched.get();
        if (cached != null && response.status() == 304) {
            return Optional.of(new ProxyFormat.Fetched(200, cached.body(), cached.headers()));
        }
        if (response.status() == 200) {
            String etag = response.header("ETag");
            String lastModified = response.header("Last-Modified");
            if ((etag != null || lastModified != null) && response.body().length <= MAX_BODY) {
                store(url, new Cached(response.body(), response.headers(), etag, lastModified,
                        sequence.incrementAndGet()));
            } else {
                // Drop any prior entry with its bytes subtracted; a bare remove would leave the total drifting high
                // until the eviction loop evicted every fresh entry.
                Cached previous = cache.remove(url);
                if (previous != null) {
                    bytes.addAndGet(-previous.body().length);
                }
            }
        }
        return fetched;
    }

    @Override
    public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) throws IOException {
        return delegate.download(url, requestHeaders);
    }

    @Override
    public Optional<ProxyFormat.Head> head(URI url, Map<String, String> requestHeaders) throws IOException {
        // A HEAD carries no body to revalidate, so it passes through to the delegate's real HEAD, as download does.
        return delegate.head(url, requestHeaders);
    }

    @Override
    public List<Metric> metrics() {
        return List.of(
                Metric.bounded("jenrepo.proxy.revalidation.bytes",
                        "Cached proxied-index body bytes held for conditional revalidation, against the byte ceiling "
                                + "past which the oldest entries are evicted - a used-vs-available signal whose usage() "
                                + "fraction shows how close the cache is to thrashing back toward a full re-download.",
                        bytes.get(), MAX_TOTAL, "bytes"),
                Metric.gauge("jenrepo.proxy.revalidation.entries",
                        "Proxied mutable indexes currently remembered with their ETag/Last-Modified validator, so a "
                                + "re-fetch is a conditional request whose 304 saves the transfer; bounded by the byte "
                                + "ceiling, not a fixed entry count.",
                        cache.size(), ""));
    }

    @Override
    public List<HealthCheck> healthChecks() {
        return List.of(HealthCheck.up("jenrepo.proxy.revalidation",
                "The revalidation cache is installed and saving index transfers by revalidating remembered bodies "
                        + "against the upstream."));
    }

    private static Map<String, String> conditional(Map<String, String> requestHeaders, Cached cached) {
        if (cached == null) {
            return requestHeaders;
        }
        Map<String, String> headers = new LinkedHashMap<>(requestHeaders);
        if (cached.etag() != null) {
            headers.put("If-None-Match", cached.etag());
        }
        if (cached.lastModified() != null) {
            headers.put("If-Modified-Since", cached.lastModified());
        }
        return headers;
    }

    private void store(URI url, Cached cached) {
        Cached previous = cache.put(url, cached);
        long total = bytes.addAndGet(cached.body().length - (previous == null ? 0 : previous.body().length));
        if (total <= MAX_TOTAL) {
            return;
        }
        // Evict oldest-first by fetch sequence until the total is under the ceiling. The atomic remove(key, value)
        // keeps the byte accounting exact when two threads evict at once: only a removal that took effect subtracts.
        List<Map.Entry<URI, Cached>> entries = new ArrayList<>(cache.entrySet());
        entries.sort(Comparator.comparingLong(entry -> entry.getValue().sequence()));
        Iterator<Map.Entry<URI, Cached>> victims = entries.iterator();
        while (bytes.get() > MAX_TOTAL && victims.hasNext()) {
            Map.Entry<URI, Cached> victim = victims.next();
            if (cache.remove(victim.getKey(), victim.getValue())) {
                bytes.addAndGet(-victim.getValue().body().length);
            }
        }
    }
}
