package build.jenesis.repository.compliance.spi.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.DenyListPolicy;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.compliance.VulnerabilityPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A subject that names no version - what a proxied index or a packument screens as - is asked of no advisory feed,
 * while the deny-list still reads its coordinate. The feed here refuses every query the way a fail-closed feed
 * refuses one it cannot answer, so a gate that asked it would fail the screen of a document that names no artifact.
 */
class VersionlessSubjectTest {

    private static final AdvisorySource REFUSES_EVERY_QUERY = new AdvisorySource() {

        @Override
        public List<Advisory> advisories(String ecosystem, String coordinate, String version) {
            throw new UncheckedIOException(new IOException("400 invalid query for " + coordinate + "@" + version));
        }

        @Override
        public Freshness freshness() {
            return Freshness.NEVER;
        }
    };

    private static final ComplianceGate GATE = new ComplianceGate(new VulnerabilityPolicy(Severity.HIGH),
            REFUSES_EVERY_QUERY).denyList(new DenyListPolicy(List.of("com.evil:*")));

    @Test
    void an_index_is_asked_of_no_feed() {
        ComplianceGate.Assessment index =
                GATE.assessUnclaimed(new ComplianceGate.Subject("Maven", "com.acme:lib", "", List.of()));

        assertThat(index.verdict()).isEqualTo(Verdict.ALLOW);
        assertThat(index.findings()).isEmpty();
    }

    @Test
    void a_version_is_asked_of_the_feed() {
        assertThatThrownBy(() -> GATE.assessUnclaimed(
                new ComplianceGate.Subject("Maven", "com.acme:lib", "1.0", List.of())))
                .as("the feed is consulted for a subject that names a version, and its refusal surfaces")
                .hasMessageContaining("com.acme:lib@1.0");
    }

    @Test
    void the_deny_list_still_reads_a_versionless_coordinate() {
        ComplianceGate.Assessment denied =
                GATE.assessUnclaimed(new ComplianceGate.Subject("Maven", "com.evil:tool", "", List.of()));

        assertThat(denied.verdict()).as("an index of a denied package is withheld by name").isEqualTo(Verdict.REJECT);
    }
}
