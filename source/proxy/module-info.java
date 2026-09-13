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
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.50.0 SHA-256/4667724877f1d37a689202da191e23efa7657c62eef93ccdac406eccfe5cdd0a
 * @jenesis.pin org.jspecify/jspecify 1.0.1 SHA-256/070d75f261fe4c5b8202508366715f7f2d4660f88c8ef7e6d3575e48c9683b66
 * @jenesis.pin org.slf4j/slf4j-api 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
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
