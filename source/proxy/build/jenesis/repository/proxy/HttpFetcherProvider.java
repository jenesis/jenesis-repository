package build.jenesis.repository.proxy;

import module java.base;
import build.jenesis.repository.format.FetcherProvider;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.store.Durations;
import build.jenesis.repository.store.Features;

/**
 * Discovers the HTTP upstream fetcher, composed with the proxy's caches: index revalidation always on (it never serves
 * stale bytes, it only saves the transfer), and a definite upstream {@code 404} remembered for {@code proxy-miss-ttl}
 * (a minute by default, {@code 0} disabling it). Without this module there is no upstream connectivity - no
 * pull-through, no imports.
 */
public final class HttpFetcherProvider implements FetcherProvider {

    @Override
    public String name() {
        return "http";
    }

    @Override
    public Optional<ProxyFormat.Fetcher> create(UnaryOperator<String> config) {
        RevalidatingFetcher revalidating = new RevalidatingFetcher(new HttpFetcher());
        Duration missTtl = missTtl(config.apply("proxy-miss-ttl"));
        if (missTtl.compareTo(Duration.ZERO) > 0) {
            return Optional.of(new NegativeCachingFetcher(revalidating, missTtl));
        }
        return Optional.of(revalidating);
    }

    /** The negative-cache window: a minute when unset, none for {@code 0} or {@code off}, else a duration in the
     *  deployment's grammar ({@code PT90S}, {@code 90s}, {@code 5m}). */
    private static Duration missTtl(String value) {
        return Durations.dial(value, Features.key("proxy-miss-ttl"), Duration.ofSeconds(60));
    }
}
