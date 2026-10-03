package build.jenesis.repository.compliance.spi.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.DenyListPolicy;
import build.jenesis.repository.compliance.GatePolicy;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.compliance.VulnerabilityPolicy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every finding the gate reports names the rule of the dimension that raised it, so a held version can say what it is
 * held for in an operator's words, and the assessment answers the rules that reached its verdict - not those of a
 * finding that only advised.
 */
class FindingRuleTest {

    private static final String COORDINATE = "org.example:lib";

    private static final AdvisorySource FEED = AdvisorySource.of(Map.of(COORDINATE, List.of(
            new AdvisorySource.Advisory("CVE-2099-0001", Severity.CRITICAL, false, null, List.of()),
            new AdvisorySource.Advisory("CVE-2099-0002", Severity.LOW, false, null, List.of()))));

    /** A discovered dimension that holds every subject for its own rule. */
    private static final GatePolicy LICENCE = new GatePolicy() {
        @Override
        public List<ComplianceGate.Finding> assess(ComplianceGate.Subject subject,
                                                   List<AdvisorySource.Advisory> advisories) {
            return List.of(new ComplianceGate.Finding(Verdict.QUARANTINE, "No license declared"));
        }

        @Override
        public String rule() {
            return "License";
        }
    };

    private static ComplianceGate.Assessment assess(ComplianceGate gate) {
        return gate.assess(new ComplianceGate.Subject("maven", COORDINATE, "1.0", List.of()));
    }

    @Test
    void each_finding_names_the_rule_of_the_dimension_that_raised_it() {
        ComplianceGate gate = new ComplianceGate(new VulnerabilityPolicy(Severity.CRITICAL, Verdict.QUARANTINE), FEED)
                .denyList(new DenyListPolicy(List.of(COORDINATE)).action(Verdict.QUARANTINE))
                .policies(List.of(LICENCE));

        ComplianceGate.Assessment assessment = assess(gate);

        assertThat(assessment.findings()).extracting(ComplianceGate.Finding::rule)
                .contains(ComplianceGate.VULNERABILITY_RULE, ComplianceGate.DENY_LIST_RULE, "License")
                .doesNotContainNull();
        assertThat(assessment.rules()).containsExactly(ComplianceGate.VULNERABILITY_RULE,
                ComplianceGate.DENY_LIST_RULE, "License");
    }

    @Test
    void the_rules_are_those_that_reached_the_verdict() {
        ComplianceGate gate = new ComplianceGate(new VulnerabilityPolicy(Severity.CRITICAL, Verdict.REJECT), FEED)
                .policies(List.of(LICENCE));

        ComplianceGate.Assessment assessment = assess(gate);

        assertThat(assessment.verdict()).isEqualTo(Verdict.REJECT);
        assertThat(assessment.rules()).as("the licence only held, the vulnerability refused")
                .containsExactly(ComplianceGate.VULNERABILITY_RULE);
    }
}
