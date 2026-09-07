/**
 * The plugin SPI seams the repository server exposes, extracted into their own minimal, framework-neutral module so a
 * plugin that only implements a seam ({@code source/ratelimit}, {@code source/usage}, {@code source/oidc}) requires
 * this contract module rather than the heavy Spring/Tomcat/Boot/Micrometer {@code build.jenesis.repository.server}
 * and inherits none of its closure. It is deliberately {@code java.base}-light - its only dependency beyond
 * {@code java.base} is the equally minimal {@code build.jenesis.repository.store} SPI (for {@code Features} and
 * {@code ArtifactStore}, which {@code Authorization} reads its grants through) - so it carries no Spring, Tomcat,
 * Micrometer or Jackson, exactly the "SPI contract modules stay java.base-light; the heavy deps ride the impl/bundle"
 * rule (&sect;2).
 *
 * <p>It holds the credential model ({@code Authorization}) and the plugin seams the server
 * {@code uses} - the unique ones resolving through the shared {@code Providers} primitives, so an explicitly selected
 * implementation that no provider answers to fails at resolution rather than degrading to the seam's {@code NONE}
 * sentinel (&sect;9): the rate limiter ({@code RateLimiter} / {@code RateLimiterProvider}), the credential usage
 * tracker
 * ({@code KeyUsageTracker} / {@code KeyUsageTrackerProvider}), the workload-identity token exchange
 * ({@code TokenExchange} / {@code TokenExchangeProvider}), the {@code /api/capabilities} contributor
 * ({@code CapabilityContributor}) and the import-edge ownership signal ({@code ImportEdgeProvider}). The
 * {@code resolve}/{@code installed} static discovery methods live on the provider types here, so the {@code uses}
 * clauses for them sit in this module; the server {@code requires transitive} this module, so every existing
 * {@code requires build.jenesis.repository.server} consumer still sees the moved types unchanged.
 *
 * @jenesis.release 25
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.49.0 SHA-256/3b1003e51b8ae56fdbd7c71073e81d1683b97e6c4dff5a9151164d59b769d13c
 * @jenesis.pin org.jspecify/jspecify 1.0.0 SHA-256/1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 */
module build.jenesis.repository.server.spi {
    requires build.jenesis.repository.scope;
    requires transitive build.jenesis.repository.store;
    exports build.jenesis.repository.server.spi;
    uses build.jenesis.repository.server.spi.CapabilityContributor;
    uses build.jenesis.repository.server.spi.RateLimiterProvider;
    uses build.jenesis.repository.server.spi.KeyUsageTrackerProvider;
    uses build.jenesis.repository.server.spi.TokenExchangeProvider;
    uses build.jenesis.repository.server.spi.ImportEdgeProvider;
}
