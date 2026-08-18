/**
 * The format contract suite: the JUnit driver for the testkit's {@code FormatContract}, one fixture per
 * {@code RepositoryFormat} the core ships, and the completeness census that keeps the two in step.
 *
 * <p>The suite exists because the serve-side contract was shared in prose only: {@code test/format/{maven,jenesis,oci,
 * raw}} each hand-wrote their own idea of what a {@code HEAD}, a traversal-shaped path or a generated index promises,
 * and the four drifted - the raw {@code HEAD} answered without a length its three peers set, and three of the four had
 * no format-seam traversal screen at all. Here the contract is stated once in the testkit and every format runs all of
 * it through a {@link build.jenesis.repository.format.testkit.FormatFixture} against a real
 * {@code FilesystemArtifactStore} rooted at a JUnit {@code @TempDir} - no
 * HTTP server, no registry, no network.
 *
 * <p>This module deliberately requires all four format implementations and reaches them only through
 * {@code RepositoryFormat.installed} - the way a dispatcher does - so it is simultaneously the runtime-discovery graph
 * the census needs: a format module omitted here disappears from {@code ServiceLoader}, and the census fails because
 * the source {@code provides} scan still declares it.
 *
 * <p>It also {@code provides} the one {@code PublishInterceptor} the withhold leg needs. A {@code publish/}-namespace
 * format is retracted by the interceptor chain answering {@code withheld} - the seam a downstream compliance screen
 * implements and the core ships empty - so without a discovered screen there is no way to hold a Maven, Jenesis
 * or raw version at all. The interceptor is a two-line delegation to the testkit's {@code ContractHold} convention.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.format.testkit
 * @jenesis.pin com.fasterxml.jackson.core/jackson-annotations 2.22 SHA-256/21ddb598807d3a51a876704eb979d9296e1c6a6f47ab1826ff88c6d6a127a2d0
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
 * @jenesis.pin tools.jackson.core/jackson-core 3.2.0 SHA-256/5e353ce53c6901105dfcbf183e3220c17072e334e552b818a4bb1b99decea596
 * @jenesis.pin tools.jackson.core/jackson-databind 3.2.0 SHA-256/3ef94a3dddeafc247c50230fad0315981b2ce4ae6e91cfb4368a86f328904e4f
 */
open module build.jenesis.repository.format.contract.test {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.testkit;
    requires build.jenesis.repository.contract.testkit;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.format.maven;
    requires build.jenesis.repository.format.jenesis;
    requires build.jenesis.repository.format.oci;
    requires build.jenesis.repository.format.raw;
    requires org.junit.jupiter;
    requires org.assertj.core;

    // Discovery is part of what is under test: the census enumerates what ServiceLoader really sees in this graph and
    // compares it against the source `provides` scan, so this module loads the SPI itself rather than through the
    // RepositoryFormat.installed static. The same `uses`-in-a-test-module shape test/store/contract already carries.
    uses build.jenesis.repository.format.RepositoryFormat;

    // The withhold leg's screen: a PublishInterceptor IS a PublicationObserver, discovered through the single seam and
    // split into the verdict chain by instanceof.
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.contract.test.ContractHoldInterceptor;
}
