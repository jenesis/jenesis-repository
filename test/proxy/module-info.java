/**
 * Focused unit tests for the proxy caches' observability adoption - that the two composed {@link
 * build.jenesis.repository.proxy.RevalidatingFetcher} and {@link build.jenesis.repository.proxy.NegativeCachingFetcher}
 * decorators are each an {@link build.jenesis.repository.observation.ObservabilitySource} reporting their bounded
 * {@code jenreg.proxy.*} used-vs-available gauges (the remembered upstream misses against the map bound, the cached
 * index bytes against the byte ceiling) and a presence health check, all collected into the single {@link
 * build.jenesis.repository.observation.ObservabilityReport} view - exercised without the server, Micrometer or any
 * network through a stub upstream fetcher. The caches' proxying behaviour itself is covered by the server test module.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.proxy
 * @jenesis.alias wiremock.core org.wiremock/wiremock-core
 * @jenesis.alias wiremock.jetty org.wiremock/wiremock-jetty
 * @jenesis.alias wiremock.httpclient org.wiremock/wiremock-httpclient-apache5
 * @jenesis.bom pin-repository.properties
 */
open module build.jenesis.repository.proxy.test {
    requires build.jenesis.repository.proxy;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.observation;
    requires org.junit.jupiter;
    requires org.assertj.core;

    requires wiremock.core;
    requires wiremock.jetty;
    requires wiremock.httpclient;
}
