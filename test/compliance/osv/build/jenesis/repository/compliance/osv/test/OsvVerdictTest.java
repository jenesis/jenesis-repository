package build.jenesis.repository.compliance.osv.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.compliance.VulnerabilityPolicy;
import build.jenesis.repository.compliance.osv.OsvAdvisorySource;
import build.jenesis.repository.settings.CoreDefaults;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A verdict computed end to end from a recorded OSV answer, under the thresholds a deployment ships with: the
 * source parses the record, the vulnerability dimension bands it, and the gate folds it into the verdict a publish
 * receives. {@link OsvAdvisorySourceTest} owns the parsing; this owns what the parsed answer <em>decides</em>, which
 * is the question an operator asks of a feed.
 */
class OsvVerdictTest {

    /** Trimmed from an osv.dev {@code /v1/query} answer for a package with one critical and one moderate advisory. */
    private static final String RECORDED = """
            {"vulns":[
              {"id":"GHSA-jfh8-c2jp-5v3q","summary":"Remote code injection in Log4j",
               "aliases":["CVE-2021-44228"],"database_specific":{"severity":"CRITICAL"},
               "affected":[{"package":{"ecosystem":"Maven","name":"org.apache.logging.log4j:log4j-core"},
                            "ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"2.0-beta9"},{"fixed":"2.15.0"}]}]}]},
              {"id":"GHSA-8489-44mv-ggj8","summary":"Improper input validation in Log4j",
               "aliases":["CVE-2021-44832"],"database_specific":{"severity":"MODERATE"}}
            ]}""";

    private static final ComplianceGate.Subject LOG4J = new ComplianceGate.Subject(
            "Maven", "org.apache.logging.log4j:log4j-core", "2.14.1",
            List.of(new ComplianceGate.DeclaredLicense("Apache-2.0", null)));

    /** The gate as a deployment that switched the feed on and named no other dial builds it. */
    private static ComplianceGate shipped(String answer) {
        return new ComplianceGate(new VulnerabilityPolicy(Severity.valueOf(CoreDefaults.VULNERABILITY_THRESHOLD))
                .action(Verdict.valueOf(CoreDefaults.VULNERABILITY_ACTION)),
                new OsvAdvisorySource(_ -> answer));
    }

    @Test
    void a_recorded_critical_advisory_decides_the_shipped_action() {
        ComplianceGate.Assessment assessment = shipped(RECORDED).assess(LOG4J);

        assertThat(assessment.verdict()).isEqualTo(Verdict.valueOf(CoreDefaults.VULNERABILITY_ACTION));
        assertThat(assessment.findings()).extracting(ComplianceGate.Finding::detail)
                .as("the reason names the advisory, and only the one at or above the shipped threshold")
                .anySatisfy(detail -> assertThat(detail).contains("GHSA-jfh8-c2jp-5v3q"))
                .noneSatisfy(detail -> assertThat(detail).contains("GHSA-8489-44mv-ggj8"));
    }

    @Test
    void an_answer_with_no_advisories_admits_the_package() {
        assertThat(shipped("{\"vulns\":[]}").assess(LOG4J).verdict()).isEqualTo(Verdict.ALLOW);
    }
}
