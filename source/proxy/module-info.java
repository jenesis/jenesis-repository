/**
 * Upstream HTTP connectivity: a {@link build.jenesis.repository.format.FetcherProvider} answering to {@code http}, the
 * real HTTP fetcher composed with index revalidation and negative caching of misses - the machinery behind pull-through
 * proxying and imports. Discovered with {@code ServiceLoader}; without it a deployment serves local content only. The
 * caches report bounded {@code jenrepo.proxy.*} signals through the registry-free observation SPI, and the settings SPI
 * carries the throughput floor and deadline dials.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.proxy {
    requires build.jenesis.repository.net.http;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.observation;
    requires build.jenesis.repository.settings;
    requires java.net.http;
    exports build.jenesis.repository.proxy;
    provides build.jenesis.repository.format.FetcherProvider
            with build.jenesis.repository.proxy.HttpFetcherProvider;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.proxy.ProxySettingsContributor;
}
