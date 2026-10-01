package build.jenesis.repository.proxy;

import module java.base;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilitySource;
import build.jenesis.repository.observation.TaskStatus;

/**
 * A {@link ProxyFormat.Fetcher} decorator that remembers an upstream {@code 404} for a short window, so a build tool's
 * probes for what is not upstream - a version range, a missing {@code SNAPSHOT}, an optional classifier, a guessed
 * {@code .sha256} - are answered from memory instead of multiplying upstream load and risking its rate limit. Only a
 * definite {@code 404} is cached; transport failures and {@code 401}/{@code 403} are transient or resolvable, and a
 * success passes through. An entry expires after the ttl, so a newly published artifact is seen within it. It decorates
 * {@link #fetch}, {@link #download} and {@link #head}, keyed by URL, concurrent-safe, in a map that is swept of expired
 * entries when full and cleared outright if still full, so it never exceeds its cap.
 *
 * <p>It is its own {@link ObservabilitySource}: {@code jenrepo.proxy.negativecache.entries}, a bounded gauge against
 * the map's cap, and a {@code jenrepo.proxy.negativecache} health check. Expiry is swept lazily on the record path, so
 * {@link #taskStatuses()} is empty.
 */
public final class NegativeCachingFetcher implements ProxyFormat.Fetcher, ObservabilitySource {

    private static final int MAX_ENTRIES = 16_384;

    private final ProxyFormat.Fetcher delegate;
    private final Duration ttl;
    private final Clock clock;
    private final ConcurrentMap<URI, Instant> misses = new ConcurrentHashMap<>();

    public NegativeCachingFetcher(ProxyFormat.Fetcher delegate, Duration ttl) {
        this(delegate, ttl, Clock.systemUTC());
    }

    /** With a {@link Clock}, so a test can advance time past an entry's expiry without sleeping. */
    public NegativeCachingFetcher(ProxyFormat.Fetcher delegate, Duration ttl, Clock clock) {
        this.delegate = delegate;
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) throws IOException {
        if (cached(url)) {
            return Optional.of(new ProxyFormat.Fetched(404, new byte[0], Map.of()));
        }
        Optional<ProxyFormat.Fetched> fetched = delegate.fetch(url, requestHeaders);
        if (fetched.isPresent() && fetched.get().status() == 404) {
            record(url);
        }
        return fetched;
    }

    @Override
    public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) throws IOException {
        if (cached(url)) {
            return Optional.of(new ProxyFormat.Download(404, InputStream.nullInputStream(), Map.of()));
        }
        Optional<ProxyFormat.Download> download = delegate.download(url, requestHeaders);
        if (download.isPresent() && download.get().status() == 404) {
            record(url);
        }
        return download;
    }

    @Override
    public Optional<ProxyFormat.Head> head(URI url, Map<String, String> requestHeaders) throws IOException {
        // A HEAD probes the same URL a fetch would, so a remembered 404 answers it too, and a definite 404 from the
        // delegate's HEAD is remembered - one cache across all three verbs.
        if (cached(url)) {
            return Optional.of(new ProxyFormat.Head(404, Map.of()));
        }
        Optional<ProxyFormat.Head> head = delegate.head(url, requestHeaders);
        if (head.isPresent() && head.get().status() == 404) {
            record(url);
        }
        return head;
    }

    /** This cache's signals and those of the fetchers it wraps: the context holds only the outermost fetcher, so the
     *  chain beneath reports through it. */
    @Override
    public List<Metric> metrics() {
        return Stream.concat(Stream.of(Metric.bounded("jenrepo.proxy.negativecache.entries",
                "Upstream 404s currently remembered, so a build tool's re-probes for a missing artifact are answered "
                        + "from memory rather than re-hitting the upstream, against the bounded map size past which a "
                        + "fresh miss first sweeps expired entries - a used-vs-available signal on the very "
                        + "memory-exhaustion vector the bound is there to cap.",
                misses.size(), MAX_ENTRIES, "")),
                delegate instanceof ObservabilitySource wrapped ? wrapped.metrics().stream() : Stream.empty())
                .toList();
    }

    @Override
    public List<HealthCheck> healthChecks() {
        return Stream.concat(Stream.of(HealthCheck.up("jenrepo.proxy.negativecache",
                "The negative cache is installed and remembering upstream misses so repeated probes are answered "
                        + "from memory.")),
                delegate instanceof ObservabilitySource wrapped ? wrapped.healthChecks().stream() : Stream.empty())
                .toList();
    }

    @Override
    public List<TaskStatus> taskStatuses() {
        return delegate instanceof ObservabilitySource wrapped ? wrapped.taskStatuses() : List.of();
    }

    private boolean cached(URI url) {
        Instant recordedAt = misses.get(url);
        if (recordedAt == null) {
            return false;
        }
        if (clock.instant().isBefore(recordedAt.plus(ttl))) {
            return true;
        }
        misses.remove(url, recordedAt);
        return false;
    }

    private void record(URI url) {
        if (misses.size() >= MAX_ENTRIES) {
            Instant now = clock.instant();
            misses.values().removeIf(recordedAt -> !now.isBefore(recordedAt.plus(ttl)));
            if (misses.size() >= MAX_ENTRIES) {
                // Still-live misses fill the map and nothing expired: drop the whole map rather than grow past the cap.
                // Forgetting misses only costs re-probes.
                misses.clear();
            }
        }
        misses.put(url, clock.instant());
    }
}
