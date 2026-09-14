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
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
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
