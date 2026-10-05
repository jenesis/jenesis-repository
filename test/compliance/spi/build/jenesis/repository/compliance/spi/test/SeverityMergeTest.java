package build.jenesis.repository.compliance.spi.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.AdvisorySource.Advisory;
import build.jenesis.repository.compliance.Severity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two reports of one vulnerability merge to the band that says the most, and a source that could not score it is
 * never overruled by one that claims nothing is severe: the merged advisory stays unknown, which a severity floor
 * holds.
 */
class SeverityMergeTest {

    private static final String CVE = "CVE-2026-0001";

    @Test
    void a_band_a_source_read_outranks_one_no_source_could_read() {
        assertThat(Severity.strongest(Severity.UNKNOWN, Severity.HIGH)).isEqualTo(Severity.HIGH);
        assertThat(Severity.strongest(Severity.LOW, Severity.UNKNOWN)).isEqualTo(Severity.LOW);
        assertThat(Severity.strongest(Severity.HIGH, Severity.CRITICAL)).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void nothing_severe_is_no_answer_to_a_source_that_could_not_score() {
        assertThat(Severity.strongest(Severity.UNKNOWN, Severity.NONE)).isEqualTo(Severity.UNKNOWN);
        assertThat(Severity.strongest(Severity.NONE, Severity.UNKNOWN)).isEqualTo(Severity.UNKNOWN);
        assertThat(Severity.strongest(Severity.NONE, Severity.NONE)).isEqualTo(Severity.NONE);
    }

    @Test
    void merged_reports_of_one_cve_keep_the_unknown_band_a_floor_holds() {
        Advisory unscored = new Advisory("GHSA-aaaa-bbbb-cccc", Severity.UNKNOWN, false, null, List.of(CVE), "");
        Advisory nothingSevere = new Advisory(CVE, Severity.NONE, false, "1.2.4", List.of(CVE), "");

        assertThat(AdvisorySource.merged(List.of(unscored, nothingSevere))).singleElement().satisfies(advisory -> {
            assertThat(advisory.severity()).isEqualTo(Severity.UNKNOWN);
            assertThat(advisory.severity().compareTo(Severity.CRITICAL))
                    .as("a floor at CRITICAL compares ordinally and holds the merged advisory")
                    .isPositive();
            assertThat(advisory.fixed()).isEqualTo("1.2.4");
        });
    }

    @Test
    void merged_reports_of_one_cve_take_the_band_a_scored_source_gave() {
        Advisory unscored = new Advisory(CVE, Severity.UNKNOWN, false, "1.2.4", List.of(CVE), "");
        Advisory scored = new Advisory("GHSA-aaaa-bbbb-cccc", Severity.HIGH, false, null, List.of(CVE), "");

        assertThat(AdvisorySource.merged(List.of(unscored, scored)))
                .singleElement().extracting(Advisory::severity).isEqualTo(Severity.HIGH);
    }
}
