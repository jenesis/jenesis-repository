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

    @Test
    void a_walk_of_a_channel_reports_its_paths_under_the_channel_its_root_names() throws IOException {
        CannedFetcher fetcher = new CannedFetcher()
                .on("http://source.local/repository/default/conda/realchan/channeldata.json", 200,
                        "{\"subdirs\":[\"noarch\"]}")
                .on("http://source.local/repository/default/conda/realchan/noarch/repodata.json", 200,
                        "{\"packages\":{\"acme-1.0-0.tar.bz2\":{}}}");
        List<ProxyFormat.Coordinate> walked = new CondaFormat()
                .enumerate(fetcher, URI.create("http://source.local/repository/default/conda/realchan/")).toList();
        assertThat(walked).extracting(ProxyFormat.Coordinate::path).containsExactly("realchan/noarch/acme-1.0-0.tar.bz2");
        assertThat(walked).extracting(ProxyFormat.Coordinate::url).containsExactly(
                URI.create("http://source.local/repository/default/conda/realchan/noarch/acme-1.0-0.tar.bz2"));
    }

    @Test
    void the_repository_an_index_is_rooted_at_is_the_last_segment_of_its_path() {
        assertThat(ProxyFormat.repository(URI.create("http://host/repository/default/releases/realrepo")))
                .contains("realrepo");
        assertThat(ProxyFormat.repository(URI.create("http://host/repository/default/releases/realrepo//")))
                .contains("realrepo");
        assertThat(ProxyFormat.repository(URI.create("http://host/"))).isEmpty();
        assertThat(ProxyFormat.repository(URI.create("http://host"))).isEmpty();
    }
}
