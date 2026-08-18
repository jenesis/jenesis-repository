/**
 * Focused unit tests for the proxy caches' observability adoption - that the two composed {@link
 * build.jenesis.repository.proxy.RevalidatingFetcher} and {@link build.jenesis.repository.proxy.NegativeCachingFetcher}
 * decorators are each an {@link build.jenesis.repository.observation.ObservabilitySource} reporting their bounded
 * {@code jenesis.proxy.*} used-vs-available gauges (the remembered upstream misses against the map bound, the cached
 * index bytes against the byte ceiling) and a presence health check, all collected into the single {@link
 * build.jenesis.repository.observation.ObservabilityReport} view - exercised without the server, Micrometer or any
 * network through a stub upstream fetcher. The caches' proxying behaviour itself is covered by the server test module.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.proxy
 * @jenesis.alias wiremock.standalone org.wiremock/wiremock-standalone
 * @jenesis.pin net.bytebuddy/byte-buddy 1.18.3 SHA-256/d78396e3c5bce3f2865c9186647481e5589d34cacc632484715b686108d17c66
 * @jenesis.pin org.apiguardian/apiguardian-api 1.1.2 SHA-256/b509448ac506d607319f182537f0b35d71007582ec741832a1f111e5b5b70b38
 * @jenesis.pin org.assertj.core 3.27.7
 * @jenesis.pin org.assertj/assertj-core 3.27.7 SHA-256/c4a445426c3c2861666863b842cc4ec7bbb1c4226fefd370b6d2fe83d6c4ff0f
 * @jenesis.pin org.jspecify/jspecify 1.0.0 SHA-256/1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab
 * @jenesis.pin org.junit.jupiter 6.0.3
 * @jenesis.pin org.junit.jupiter.api 6.0.3
 * @jenesis.pin org.junit.jupiter/junit-jupiter 6.0.3 SHA-256/784b65815f479a0c99a9d3a573b142e2a525efb6025d97f751b19e72f90aeda3
 * @jenesis.pin org.junit.jupiter/junit-jupiter-api 6.0.3 SHA-256/d655d7e6f0c7ae07f10a2f3bbaaebb6d30e9b26204a068ad9e9b3950aa28792c
 * @jenesis.pin org.junit.jupiter/junit-jupiter-engine 6.0.3 SHA-256/1e2fab61ad27ea08fc7c70dd9677cf8c6d1ae5434d42dcfdd633b12c7e7c04d0
 * @jenesis.pin org.junit.jupiter/junit-jupiter-params 6.0.3 SHA-256/cf2947e2302b9f8c8a059259a277881c1cadae8fbc2514c16a925cfeb7beb2e5
 * @jenesis.pin org.junit.platform.commons 6.0.3
 * @jenesis.pin org.junit.platform.console 6.0.3
 * @jenesis.pin org.junit.platform/junit-platform-commons 6.0.3 SHA-256/39f262d09c3d52719fe0b77f080e90a3695e285d779a41b232e17963ae5da200
 * @jenesis.pin org.junit.platform/junit-platform-console 6.0.3
 * @jenesis.pin org.junit.platform/junit-platform-engine 6.0.3 SHA-256/491e9e4f745f161b8a8e4186a1a7c6a450ea12c70930c9aedae427215301d947
 * @jenesis.pin org.junit.platform/junit-platform-launcher 6.0.3
 * @jenesis.pin org.junit.platform/junit-platform-reporting 6.0.3
 * @jenesis.pin org.opentest4j 1.3.0
 * @jenesis.pin org.opentest4j.reporting/open-test-reporting-tooling-spi 0.2.4
 * @jenesis.pin org.opentest4j/opentest4j 1.3.0 SHA-256/48e2df636cab6563ced64dcdff8abb2355627cb236ef0bf37598682ddf742f1b
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 * @jenesis.pin org.wiremock/wiremock-standalone 4.0.0-beta.38 SHA-256/76353b4feae89bff66583a48010272c452df74d969452bf50977afe9db441211
 */
open module build.jenesis.repository.proxy.test {
    requires build.jenesis.repository.proxy;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.observation;
    requires org.junit.jupiter;
    requires org.assertj.core;

    // WireMock's shaded HttpClient5 reaches for jdk/net/Sockets at runtime; an automatic module roots no requires, so
    // the consumer must root jdk.net explicitly or the tests throw NoClassDefFoundError: jdk/net/Sockets.
    requires jdk.net;
    requires wiremock.standalone;
}
