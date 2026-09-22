/**
 * The JUnit-free driver kit the per-ecosystem gateway suites share: the in-process format driver
 * ({@code FormatDrive} - a capturing {@code FormatExchange} and a minimal in-memory {@code ArtifactStore}), the canned
 * enumeration fetcher ({@code CannedFetcher}) and the hermetic WireMock upstream the proxy legs pull through
 * ({@code LoopbackUpstream}).
 *
 * <p><strong>Why a source module.</strong> A JUnit test module is a <em>leaf</em> - no test module may require another
 * - so the moment {@code test/gateway} is split per ecosystem, the helpers those suites shared inside one
 * module have nowhere to live but an exported source module. This is the same shape {@code source/ecosystem-testkit}
 * and the {@code source/store/testkit} already have, and the alternative is the duplication drift 
 * had to unwind for {@code Requirement} (twelve copies).
 *
 * <p><strong>Deliberately narrow.</strong> Everything in here is format-agnostic and store-agnostic: the kit reads the
 * {@code RepositoryFormat}/{@code ProxyFormat} SPI, the {@code ArtifactStore} SPI and WireMock, and <em>nothing</em>
 * else. That is the load-bearing property of the split - the kit sits in the rebuild cone of every per-ecosystem
 * module, so a {@code requires} of a format implementation, of {@code compliance}, {@code gc} or {@code metadata}
 * here would put that module's churn back in front of all sixteen ecosystems. The heavier helpers that still live in
 * {@code test/gateway} ({@code Gc}, {@code TestMetadataStore}, {@code CountingStore}, the Nexus/Artifactory
 * incumbent rigs, {@code LanguageSeeds}) are deliberately left there until /c moves a consumer that needs them,
 * and each carries its own extra requires when it comes.
 *
 * <p><strong>Test support, never runtime.</strong> Nothing here provides a service, so the module is inert on a
 * runtime graph. It is not a product module and no bundle, application shell or plugin may require it.
 *
 * <p><strong>Consumer gotcha.</strong> WireMock ships no module descriptor, so every module that requires this one
 * needs its OWN {@code @jenesis.alias wiremock.core ...} and {@code @jenesis.alias wiremock.jetty ...} tags plus
 * {@code requires wiremock.core;} and {@code requires wiremock.jetty;} - the same note
 * the ecosystem run carries. The jetty half is not optional: WireMock 4 splits the
 * {@code HttpServerFactory} out of the core artifact and registers it as a {@code ServiceLoader} extension.
 *
 * @jenesis.release 25
 * @jenesis.alias wiremock.core org.wiremock/wiremock-core
 * @jenesis.alias wiremock.jetty org.wiremock/wiremock-jetty
 * @jenesis.alias wiremock.httpclient org.wiremock/wiremock-httpclient-apache5
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.gateway.testkit {
    // Test support only; nothing here ships, and nothing here provides a service. It cannot be
    // dropped without editing every descriptor - LicenseGraphTest is the guard.
    // The two SPIs the kit drives, and nothing more: the format protocol seam and the store it writes through.
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    // The hermetic loopback upstream. Not referenced by name in the descriptor's own requires beyond this, but the
    // alias above is what puts the descriptor-less jar on the module path at all.
    requires wiremock.core;
    requires wiremock.jetty;
    requires wiremock.httpclient;
    requires jdk.net;
    // The formats are discovered exactly as the server discovers them; the format implementations export
    // nothing, so a driver reaches them through the SPI's own ServiceLoader seam and must declare the use here, in the
    // module that calls it.
    uses build.jenesis.repository.format.RepositoryFormat;
    exports build.jenesis.repository.gateway.testkit;
}
