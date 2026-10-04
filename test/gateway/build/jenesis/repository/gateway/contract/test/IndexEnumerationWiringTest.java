package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.composer.ComposerFormat;
import build.jenesis.repository.format.conda.CondaFormat;
import build.jenesis.repository.format.debian.DebianFormat;
import build.jenesis.repository.format.nuget.NuGetFormat;
import build.jenesis.repository.format.pypi.PyPiFormat;
import build.jenesis.repository.format.rpm.RpmFormat;
import build.jenesis.repository.gateway.testkit.CannedFetcher;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An import from a format's own index walks it through the format: each format a walker reads the index of answers
 * {@link ProxyFormat#enumerate} through it, so the walk asks the source for its index rather than answering the empty
 * stream that imports nothing. The walks themselves are each walker's own tests'.
 */
class IndexEnumerationWiringTest {

    @Test
    void every_format_with_an_index_walker_asks_the_source_for_its_index() {
        for (ProxyFormat format : List.of(new PyPiFormat(), new NuGetFormat(), new DebianFormat(), new RpmFormat(),
                new CondaFormat(), new ComposerFormat())) {
            CannedFetcher fetcher = new CannedFetcher();
            try {
                format.enumerate(fetcher, URI.create("http://source.local")).toList();
            } catch (IOException | RuntimeException unanswered) {
                // The source answers nothing, so the walk fails where it starts; that it started is the claim.
            }
            assertThat(fetcher.urls).as("%s walks the source's index", format.getClass().getSimpleName())
                    .isNotEmpty();
        }
    }
}
