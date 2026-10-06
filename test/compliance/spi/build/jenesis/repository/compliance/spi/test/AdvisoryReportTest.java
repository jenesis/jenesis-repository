package build.jenesis.repository.compliance.spi.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisoryReport;
import build.jenesis.repository.compliance.AdvisorySignal;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.compliance.Severity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The vulnerability report's ordering, pinned with no network and no framework: having dependents is the primary key
 * (a coordinate published versions are built against sorts above one only scored in the abstract, even when the
 * merely-scored one carries the more urgent signal), the installed signal ranks break a tie between lines with
 * dependents, and the overload knowing no dependents orders by signal then coordinate.
 */
class AdvisoryReportTest {

    private static final String DEPENDED = "org.example:depended:1.0";
    private static final String SCORED = "org.example:scored:1.0";

    @Test
    void ranks_a_coordinate_with_dependents_above_a_merely_scored_one_even_at_a_lower_signal_rank() {
        SequencedMap<String, List<AdvisorySource.Advisory>> findings = new LinkedHashMap<>();
        findings.put(SCORED, List.of(new AdvisorySource.Advisory("CVE-SCORED", Severity.CRITICAL)));
        findings.put(DEPENDED, List.of(new AdvisorySource.Advisory("CVE-USED", Severity.LOW)));

        List<AdvisoryReport.Line> lines =
                AdvisoryReport.assemble(List.of(severitySignal()), findings, Map.of(DEPENDED, 2));

        assertThat(lines).extracting(AdvisoryReport.Line::coordinate).as(
                        "the LOW with dependents sorts above the merely-scored CRITICAL - having dependents is the primary key")
                .containsExactly(DEPENDED, SCORED);
        assertThat(lines).extracting(AdvisoryReport.Line::usedBy).as("each line carries how many it is used by")
                .containsExactly(2, 0);
    }

    @Test
    void breaks_a_tie_between_lines_with_dependents_by_the_installed_signal_rank_not_by_their_count() {
        SequencedMap<String, List<AdvisorySource.Advisory>> findings = new LinkedHashMap<>();
        findings.put(DEPENDED, List.of(new AdvisorySource.Advisory("CVE-USED", Severity.LOW)));
        findings.put(SCORED, List.of(new AdvisorySource.Advisory("CVE-SCORED", Severity.CRITICAL)));

        // both have dependents, so the signal rank (CRITICAL over LOW) decides, not which has more
        List<AdvisoryReport.Line> lines =
                AdvisoryReport.assemble(List.of(severitySignal()), findings, Map.of(DEPENDED, 3, SCORED, 1));

        assertThat(lines).extracting(AdvisoryReport.Line::coordinate).containsExactly(SCORED, DEPENDED);
    }

    @Test
    void with_no_dependents_known_falls_back_to_the_signal_rank_then_coordinate() {
        SequencedMap<String, List<AdvisorySource.Advisory>> findings = new LinkedHashMap<>();
        findings.put(DEPENDED, List.of(new AdvisorySource.Advisory("CVE-USED", Severity.LOW)));
        findings.put(SCORED, List.of(new AdvisorySource.Advisory("CVE-SCORED", Severity.CRITICAL)));

        List<AdvisoryReport.Line> lines = AdvisoryReport.assemble(List.of(severitySignal()), findings);

        assertThat(lines).extracting(AdvisoryReport.Line::coordinate).as(
                        "the overload knowing no dependents orders by signal, then coordinate")
                .containsExactly(SCORED, DEPENDED);
    }

    /** A trivial signal that ranks each advisory by its CVSS band, so a test can pin the ordering without a feed. */
    private static AdvisorySignal severitySignal() {
        return new AdvisorySignal() {
            @Override
            public String name() {
                return "severity";
            }

            @Override
            public String label() {
                return "Severity";
            }

            @Override
            public int order() {
                return 0;
            }

            @Override
            public List<Value> evaluate(List<AdvisorySource.Advisory> advisories) {
                List<Value> values = new ArrayList<>(advisories.size());
                for (AdvisorySource.Advisory advisory : advisories) {
                    values.add(new Value(advisory.severity().name(), advisory.severity().ordinal()));
                }
                return values;
            }

            @Override
            public Freshness freshness() {
                // A projection of the advisories it is handed - no vendor behind it, so nothing to date-stamp.
                return Freshness.FIXED;
            }
        };
    }
}
