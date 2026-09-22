package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.composer.ComposerEnumeration;
import build.jenesis.repository.format.debian.DebianEnumeration;
import build.jenesis.repository.format.nuget.NuGetEnumeration;
import build.jenesis.repository.format.pypi.PyPiEnumeration;
import build.jenesis.repository.gateway.testkit.CannedFetcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one distinction the enumeration walks have to keep: <b>a mirror that did not answer</b> is
 * {@link ProxyFormat.Unavailable}, and <b>a mirror that answered with something we cannot walk</b> is a plain
 * {@link IOException}. Both used to be the second, which made them one event to every caller - and the two call for
 * opposite handling, because the first is the vendor having a bad afternoon and the second is a format change that
 * has silently invalidated a walk.
 *
 * <p><b>The falsifier is {@link #a_mirror_that_answers_with_a_shape_we_cannot_walk_is_not_unavailable()} and its
 * Debian twin.</b> Widen the classification - throw {@code Unavailable} for a service index advertising no flat
 * container, say, because it reads like "we could not get what we needed" - and those two cells fail here. Without
 * them the widening is invisible and its cost is paid somewhere far away: a caller that skips or stands in for an
 * unavailable mirror would begin skipping a genuine break in the format, and the walk would report success over a
 * repository it can no longer enumerate. That is the only reason this suite exists, so the {@code isNotInstanceOf}
 * lines are the point of it and not decoration.
 *
 * <p>No server and no network - the canned documents say what the peer did, which is the whole input to the
 * decision under test. The live counterpart is {@code EnumerationLiveMirrorTest}, which acts on the distinction by
 * skipping a cell whose mirror is down while still failing on one that answers wrongly.
 */
class EnumerationUnavailabilityTest {

    private static final String NUGET = "http://source.local/nuget";

    private static final String DEBIAN = "http://source.local/debian";

    private static final String COMPOSER = "http://source.local/composer/composer";

    @Test
    void a_mirror_that_says_nothing_at_all_is_unavailable_with_no_status() {
        assertThatThrownBy(() -> NuGetEnumeration.enumerate(new CannedFetcher(), URI.create(NUGET), false))
                .as("an unmapped URL is the canned fetcher's transport failure - clause 6's empty answer")
                .isInstanceOf(ProxyFormat.Unavailable.class)
                .satisfies(thrown -> {
                    ProxyFormat.Unavailable unavailable = (ProxyFormat.Unavailable) thrown;
                    assertThat(unavailable.status()).as("nothing answered, so there is no status to carry").isEmpty();
                    assertThat(unavailable.url()).as("it names the document it could not get")
                            .isEqualTo(URI.create(NUGET + "/v3/index.json"));
                });
    }

    @Test
    void a_mirror_that_declines_is_unavailable_and_carries_the_status_it_declined_with() {
        CannedFetcher fetcher = new CannedFetcher().on(NUGET + "/v3/index.json", 503, "");
        assertThatThrownBy(() -> NuGetEnumeration.enumerate(fetcher, URI.create(NUGET), false))
                .isInstanceOf(ProxyFormat.Unavailable.class)
                .satisfies(thrown -> assertThat(((ProxyFormat.Unavailable) thrown).status())
                        .as("a 503 is the peer declining to answer, and the caller can act on which refusal it was")
                        .hasValue(503));
    }

    @Test
    void a_mirror_that_answers_with_a_shape_we_cannot_walk_is_not_unavailable() {
        CannedFetcher fetcher = new CannedFetcher()
                .on(NUGET + "/v3/index.json", 200, "{\"version\":\"3.0.0\",\"resources\":[]}");
        assertThatThrownBy(() -> NuGetEnumeration.enumerate(fetcher, URI.create(NUGET), false))
                .as("the peer answered 200; that we cannot walk what it said is a format change, never an outage")
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(ProxyFormat.Unavailable.class)
                .hasMessageContaining("No flat container");
    }

    @Test
    void an_apt_source_listing_no_suites_is_not_unavailable_either() {
        CannedFetcher fetcher = new CannedFetcher()
                .on(DEBIAN + "/dists/", 200, "<html><a href=\"../\">../</a></html>");
        assertThatThrownBy(() -> DebianEnumeration.enumerate(fetcher, URI.create(DEBIAN), false))
                .as("a served but suite-less dists/ is the archive telling us its shape, and we could not use it")
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(ProxyFormat.Unavailable.class)
                .hasMessageContaining("No suites");
    }

    @Test
    void a_composer_repository_that_declines_is_unavailable() {
        CannedFetcher fetcher = new CannedFetcher().on(COMPOSER + "/packages.json", 502, "");
        assertThatThrownBy(() -> ComposerEnumeration.enumerate(fetcher, URI.create(COMPOSER), false))
                .isInstanceOf(ProxyFormat.Unavailable.class)
                .satisfies(thrown -> assertThat(((ProxyFormat.Unavailable) thrown).status()).hasValue(502));
    }

    @Test
    void a_composer_repository_offering_no_way_to_enumerate_is_not_unavailable() {
        CannedFetcher fetcher = new CannedFetcher().on(COMPOSER + "/packages.json", 200,
                "{\"metadata-url\":\"/composer/composer/p2/%package%.json\"}");
        assertThatThrownBy(() -> ComposerEnumeration.enumerate(fetcher, URI.create(COMPOSER), false))
                .as("it served its root document; that the document offers no listing is the repository's shape")
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(ProxyFormat.Unavailable.class)
                .hasMessageContaining("Not enumerable");
    }

    @Test
    void a_mirror_that_stops_answering_mid_walk_is_unavailable_under_the_stream_wrapper() throws IOException {
        CannedFetcher fetcher = new CannedFetcher()
                .on("http://source.local/simple/", 200, "<a href=\"alpha/\">alpha</a><a href=\"beta/\">beta</a>")
                .on("http://source.local/simple/alpha/", 200, "<a href=\"a.whl\">a.whl</a>")
                .on("http://source.local/simple/beta/", 503, "");
        Stream<Map.Entry<String, URI>> walk =
                PyPiEnumeration.enumerate(fetcher, URI.create("http://source.local"), false);
        assertThatThrownBy(walk::toList)
                .as("a walk is lazy, so a later page's failure surfaces wrapped - and a caller classifying on the "
                        + "type has to unwrap rather than only catching the bare form")
                .isInstanceOf(UncheckedIOException.class)
                .cause()
                .isInstanceOf(ProxyFormat.Unavailable.class);
    }
}
