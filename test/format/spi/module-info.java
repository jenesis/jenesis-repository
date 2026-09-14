/**
 * Focused unit tests for the format SPI's shared helpers: the SSRF private-range classifier
 * {@link build.jenesis.repository.net.PrivateHosts} (its hand-rolled CGNAT and IPv6 unique-local branches the JDK
 * does not recognise) and the {@link build.jenesis.repository.format.FetcherProvider#resolve} optional-unique SPI seam
 * (explicit selection, ambiguity when more than one fetcher is enabled, a loud failure when the selection names a
 * fetcher nothing answers to, and {@code NONE} only when nothing is enabled), driven through
 * {@link java.util.ServiceLoader}-discovered stub providers registered by this module - no network, no store. It also
 * covers {@link build.jenesis.repository.format.FormatMarks}, the format family's half of the shared mark resolution
 * (which installed format owns a storage namespace or declares an ecosystem), driven over stub formats so the
 * mapping and not discovery is what is asserted. Discovery itself is covered by the one claim that needs it: two
 * installed formats may declare the SAME ecosystem - a coordinate space can be served through more than one layout -
 * and every seam that maps an ecosystem back to a layout answers over both rather than over whichever discovery
 * yielded first. That is driven over two {@code provides}-registered stub formats sharing one.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.format
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.format.test {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires org.junit.jupiter;
    requires org.assertj.core;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.test.StubTwinAlphaFormat,
                    build.jenesis.repository.format.test.StubTwinBetaFormat;
    provides build.jenesis.repository.format.FetcherProvider
            with build.jenesis.repository.format.test.StubEmptyFetcherProvider,
                    build.jenesis.repository.format.test.StubAlphaFetcherProvider,
                    build.jenesis.repository.format.test.StubBetaFetcherProvider;
}
