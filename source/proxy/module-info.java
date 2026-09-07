/**
 * The upstream HTTP connectivity as a plugin module: it {@code provides} a
 * {@link build.jenesis.repository.format.FetcherProvider} answering to {@code http}, composing the real HTTP
 * fetcher with index revalidation and negative caching of upstream misses - the machinery behind pull-through
 * proxying and repository imports. The dispatcher discovers it with {@code ServiceLoader} and names no transport;
 * a deployment without this module serves local content only (a proxy upstream is never consulted, an import is
 * refused). The composed caches are their own {@code ObservabilitySource}s reporting their bounded {@code
 * jenreg.proxy.*} used-vs-available signals, so it {@code requires} the equally minimal, registry-free
 * {@code build.jenesis.repository.observation} SPI beside the format SPI and {@code java.net.http}.
 *
 * @jenesis.release 25
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.49.0 SHA-256/3b1003e51b8ae56fdbd7c71073e81d1683b97e6c4dff5a9151164d59b769d13c
 * @jenesis.pin org.jspecify/jspecify 1.0.0 SHA-256/1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 */
module build.jenesis.repository.proxy {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.observation;
    requires java.net.http;
    exports build.jenesis.repository.proxy;
    provides build.jenesis.repository.format.FetcherProvider
            with build.jenesis.repository.proxy.HttpFetcherProvider;
    provides build.jenesis.repository.observation.ObservabilitySource
            with build.jenesis.repository.proxy.RevalidationObservability,
                 build.jenesis.repository.proxy.NegativeCacheObservability;
}
