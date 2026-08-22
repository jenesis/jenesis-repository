package build.jenesis.repository.proxy;

import module java.base;
import build.jenesis.repository.format.FetcherProvider;
import build.jenesis.repository.format.ProxyFormat;

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

    /** Parse the negative-cache window: ISO-8601 ({@code PT90S}) or the simple style Spring binds ({@code 90s},
     *  {@code 5m}); default one minute. */
    private static Duration missTtl(String value) {
        if (value == null || value.isBlank()) {
            return Duration.ofSeconds(60);
        }
        String trimmed = value.trim();
        try {
            return Duration.parse(trimmed);
        } catch (DateTimeException _) {
            long amount = Long.parseLong(trimmed.replaceAll("\\D+$", ""));
            return switch (trimmed.replaceAll("^\\d+", "").toLowerCase(Locale.ROOT)) {
                case "ms" -> Duration.ofMillis(amount);
                case "s", "" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                default -> throw new IllegalArgumentException("Cannot parse duration: " + value);
            };
        }
    }
}
