package build.jenesis.repository.compliance.contract.test;

import module java.base;
import build.jenesis.repository.compliance.AdvisorySignal;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.FeedRefresh;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.compliance.RefreshableSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drawing the advisory feeds, and saying which ones did not come back.
 *
 * <p>Two surfaces draw the feeds before reporting on a repository, and each had written the loop itself. They had
 * drifted into opposite behaviour over the same SPI: the API logged a warning per feed it could not draw, and the
 * console caught the same exceptions into an empty block - so one surface said nothing at all about a feed that
 * never loaded, and then rendered "No known vulnerabilities" over the empty result that feed produced.
 *
 * <p>These are the three answers a draw can give, and the reason the middle one is here at all: a feed can fail
 * <em>without throwing</em>. {@code refresh} returns the source's freshness precisely so a caller can tell a
 * refresh that landed from one that did not, and a source that has never fetched reports no instant. A helper that
 * only caught exceptions would call that one a success.
 */
class FeedRefreshTest {

    /** A signal that mirrors its vendor, and whatever its {@code refresh} is told to do. */
    private record Mirror(String name, Supplier<Freshness> draw) implements AdvisorySignal, RefreshableSource {

        @Override
        public String label() {
            return name;
        }

        @Override
        public int order() {
            return 0;
        }

        @Override
        public List<Value> evaluate(List<AdvisorySource.Advisory> advisories) {
            return advisories.stream().map(_ -> Value.ABSENT).toList();
        }

        @Override
        public Freshness freshness() {
            return Freshness.NEVER;
        }

        @Override
        public Freshness refresh() {
            return draw.get();
        }
    }

    private static Freshness landed() {
        return new Freshness(Optional.of(Instant.parse("2026-09-15T00:00:00Z")), true);
    }

    @Test
    void a_feed_that_throws_is_named_and_the_draw_carries_on() {
        List<String> warnings = FeedRefresh.refreshAll(List.of(
                new Mirror("kev", () -> {
                    throw new UncheckedIOException(new IOException("the mirror's store is unreachable"));
                }),
                new Mirror("epss", FeedRefreshTest::landed)));

        assertThat(warnings)
                .as("the feed that could not be drawn is named, and the one that answered is not - a draw that "
                        + "stopped at the first failure would leave the rest of the deployment's feeds undrawn")
                .singleElement().asString()
                .contains("kev")
                .contains("UncheckedIOException");
    }

    @Test
    void a_feed_that_has_never_fetched_is_named_though_it_threw_nothing() {
        // The half a try/catch cannot see. refresh() answers the source's freshness so the caller can tell a
        // refresh that landed from one that did not; NEVER means nothing has ever been fetched, and its documented
        // meaning is that the emptiness confirms nothing - which is exactly what must not read as a clean scan.
        assertThat(FeedRefresh.refreshAll(List.of(new Mirror("kev", () -> Freshness.NEVER))))
                .singleElement().asString()
                .contains("kev")
                .contains("never fetched");
    }

    @Test
    void a_deployment_whose_feeds_all_answer_produces_no_warning_at_all() {
        // The row is a qualifier on a report, so a healthy deployment must produce none: a surface that always
        // showed one would be trained away within a week.
        assertThat(FeedRefresh.refreshAll(List.of(
                new Mirror("kev", FeedRefreshTest::landed),
                new Mirror("epss", FeedRefreshTest::landed))))
                .isEmpty();
    }

    @Test
    void a_signal_that_does_not_mirror_is_not_drawn_and_is_never_named() {
        // Only a RefreshableSource has a write-role draw; a signal that queries its vendor per lookup has nothing
        // to refresh and must not be reported as a feed that failed to.
        record PerLookup(String name) implements AdvisorySignal {
            @Override
            public String label() {
                return name;
            }

            @Override
            public int order() {
                return 0;
            }

            @Override
            public List<Value> evaluate(List<AdvisorySource.Advisory> advisories) {
                return advisories.stream().map(_ -> Value.ABSENT).toList();
            }

            @Override
            public Freshness freshness() {
                return Freshness.NEVER;
            }
        }
        assertThat(FeedRefresh.refreshAll(List.of(new PerLookup("osv")))).isEmpty();
    }

    @Test
    void the_rows_are_recognisable_among_whatever_else_a_report_carries() {
        // The two ends of the agreement: a stored report's rows are free text, and the reader has to pick the
        // writer's lines out of the scan's own. This is that round trip.
        List<String> written = FeedRefresh.refreshAll(List.of(new Mirror("kev", () -> Freshness.NEVER)));
        List<String> report = new ArrayList<>(written);
        report.add("scanned 2026-09-15T00:00:00Z");
        report.add("412 versions scanned");

        assertThat(FeedRefresh.warningsIn(report))
                .as("the scan's own rows are not mistaken for feed warnings, and the feed warnings are found")
                .containsExactlyElementsOf(written);
    }
}
