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
 * The vulnerability report's ordering, pinned with no network and no framework: reachability is the primary key
 * (a coordinate confirmed to sit on a build graph sorts above one only scored in the abstract, even when the
 * merely-scored one carries the more urgent signal), the installed signal ranks break a reachability tie, and the
 * no-reachability overload keeps the prior signal-then-coordinate ordering so a deployment without a
 * reverse-dependency index is unchanged.
 */
class AdvisoryReportTest {

    private static final String REACHABLE = "org.example:reachable:1.0";
    private static final String SCORED = "org.example:scored:1.0";

    @Test
    void ranks_a_reachable_coordinate_above_a_merely_scored_one_even_at_a_lower_signal_rank() {
        SequencedMap<String, List<AdvisorySource.Advisory>> findings = new LinkedHashMap<>();
        findings.put(SCORED, List.of(new AdvisorySource.Advisory("CVE-SCORED", Severity.CRITICAL)));
        findings.put(REACHABLE, List.of(new AdvisorySource.Advisory("CVE-REACH", Severity.LOW)));

        List<AdvisoryReport.Line> lines =
                AdvisoryReport.assemble(List.of(severitySignal()), findings, Set.of(REACHABLE));

        assertThat(lines).extracting(AdvisoryReport.Line::coordinate).as(
                        "the reachable LOW sorts above the merely-scored CRITICAL - reachability is the primary key")
                .containsExactly(REACHABLE, SCORED);
    }

    @Test
    void breaks_a_reachability_tie_by_the_installed_signal_rank() {
        SequencedMap<String, List<AdvisorySource.Advisory>> findings = new LinkedHashMap<>();
        findings.put(REACHABLE, List.of(new AdvisorySource.Advisory("CVE-REACH", Severity.LOW)));
        findings.put(SCORED, List.of(new AdvisorySource.Advisory("CVE-SCORED", Severity.CRITICAL)));

        // both reachable, so the signal rank (CRITICAL over LOW) decides
        List<AdvisoryReport.Line> lines =
                AdvisoryReport.assemble(List.of(severitySignal()), findings, Set.of(REACHABLE, SCORED));

        assertThat(lines).extracting(AdvisoryReport.Line::coordinate).containsExactly(SCORED, REACHABLE);
    }

    @Test
    void with_no_reachability_falls_back_to_the_signal_rank_then_coordinate() {
        SequencedMap<String, List<AdvisorySource.Advisory>> findings = new LinkedHashMap<>();
        findings.put(REACHABLE, List.of(new AdvisorySource.Advisory("CVE-REACH", Severity.LOW)));
        findings.put(SCORED, List.of(new AdvisorySource.Advisory("CVE-SCORED", Severity.CRITICAL)));

        List<AdvisoryReport.Line> lines = AdvisoryReport.assemble(List.of(severitySignal()), findings);

        assertThat(lines).extracting(AdvisoryReport.Line::coordinate).as(
                        "the empty-reachability overload keeps the pre-signal-then-coordinate ordering")
                .containsExactly(SCORED, REACHABLE);
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
