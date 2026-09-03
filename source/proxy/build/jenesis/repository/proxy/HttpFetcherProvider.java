package build.jenesis.repository.proxy;

import module java.base;
import build.jenesis.repository.format.FetcherProvider;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.store.Durations;

/**
 * Discovers the HTTP upstream fetcher, composed with the proxy's caching behaviour: index revalidation is always
 * on (it never serves stale bytes, only saves the transfer), and a definite upstream {@code 404} is remembered for
 * {@code proxy-miss-ttl} (default a minute; {@code 0} disables the negative cache). Without this module a
 * deployment has no upstream connectivity at all - no pull-through proxying, no imports.
 */
public final class HttpFetcherProvider implements FetcherProvider {

    @Override
    public String name() {
        return "http";
    }

    @Override
    public Optional<ProxyFormat.Fetcher> create(UnaryOperator<String> config) {
        RevalidatingFetcher revalidating = new RevalidatingFetcher(new HttpFetcher());
        RevalidatingFetcher.install(revalidating);              // the discovered observability reads these
        Duration missTtl = missTtl(config.apply("proxy-miss-ttl"));
        if (missTtl.compareTo(Duration.ZERO) > 0) {
            NegativeCachingFetcher caching = new NegativeCachingFetcher(revalidating, missTtl);
            NegativeCachingFetcher.install(caching);
            return Optional.of(caching);
        }
        return Optional.of(revalidating);
    }

    /** The negative-cache window: a minute when unset, {@code 0} or {@code off} for no negative cache at all, and
     *  otherwise a duration in the deployment's one grammar ({@code PT90S}, {@code 90s}, {@code 5m}). */
    private static Duration missTtl(String value) {
        if (value == null || value.isBlank()) {
            return Duration.ofSeconds(60);
        }
        String trimmed = value.trim();
        if (trimmed.equals("0") || trimmed.equalsIgnoreCase("off")) {
            return Duration.ZERO;
        }
        return Durations.parse(trimmed);
    }
}
