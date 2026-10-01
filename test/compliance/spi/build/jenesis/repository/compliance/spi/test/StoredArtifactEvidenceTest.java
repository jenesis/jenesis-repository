package build.jenesis.repository.compliance.spi.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.GatePolicy;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.compliance.Vex;
import build.jenesis.repository.compliance.VexStatement;
import build.jenesis.repository.compliance.VexStatus;
import build.jenesis.repository.compliance.VulnerabilityPolicy;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A dimension that answers from evidence about the stored artifact: bound to the repository and the artifact as
 * stored, it adds what that evidence reports as advisories, and the gate decides them by the same threshold, VEX
 * statements and waivers a feed's advisory meets. No store is read here - the dimension records what it was bound
 * to, which is the whole of the seam's contract with the gate.
 */
class StoredArtifactEvidenceTest {

    private static final ComplianceGate.Subject IMAGE =
            new ComplianceGate.Subject("OCI", "team/app", "1.0", List.of());

    private static final AdvisorySource.Advisory OPENSSL = new AdvisorySource.Advisory("CVE-2023-0286",
            Severity.CRITICAL, false, "openssl 3.0.8", List.of("CVE-2023-0286"), "openssl 3.0.7: X.400 confusion");

    private static final String HASH = "a".repeat(64);

    private static final ArtifactDescriptor STORED = new ArtifactDescriptor("oci", "team/app", "1.0",
            "/v2/team/app/manifests/1.0", null, false, HASH, 10L);

    /** Reports {@link #OPENSSL} for the artifact stored under {@link #HASH} once it is bound to it, and nothing
     *  before - a scan report read from what the repository recorded about those bytes. */
    private record ScannedContent(ArtifactDescriptor artifact) implements GatePolicy {

        @Override
        public List<ComplianceGate.Finding> assess(ComplianceGate.Subject subject,
                                                   List<AdvisorySource.Advisory> advisories) {
            return List.of();
        }

        @Override
        public List<AdvisorySource.Advisory> advisories(ComplianceGate.Subject subject) {
            return artifact != null && HASH.equals(artifact.hash()) ? List.of(OPENSSL) : List.of();
        }

        @Override
        public GatePolicy bound(ArtifactStore repository, ArtifactDescriptor artifact) {
            return new ScannedContent(artifact);
        }
    }

    private static ComplianceGate gate() {
        return new ComplianceGate(new VulnerabilityPolicy(Severity.HIGH), AdvisorySource.none())
                .policies(List.of(new ScannedContent(null)));
    }

    @Test
    void an_unbound_dimension_reports_nothing() {
        assertThat(gate().assess(IMAGE).verdict()).isEqualTo(Verdict.ALLOW);
    }

    @Test
    void what_a_bound_dimension_reports_is_decided_by_the_vulnerability_threshold() {
        ComplianceGate.Assessment assessment = gate().bound(null, STORED).assess(IMAGE);

        assertThat(assessment.verdict()).isEqualTo(Verdict.REJECT);
        assertThat(assessment.findings()).singleElement()
                .satisfies(finding -> assertThat(finding.detail()).contains("CVE-2023-0286").contains("openssl"));
    }

    @Test
    void the_advisory_is_shared_with_every_dimension() {
        List<List<AdvisorySource.Advisory>> seen = new ArrayList<>();
        GatePolicy watching = (subject, advisories) -> {
            seen.add(advisories);
            return List.of();
        };
        new ComplianceGate(new VulnerabilityPolicy(Severity.HIGH), AdvisorySource.none())
                .policies(List.of(new ScannedContent(null), watching))
                .bound(null, STORED)
                .assess(IMAGE);

        assertThat(seen).singleElement().satisfies(advisories -> assertThat(advisories).containsExactly(OPENSSL));
    }

    @Test
    void a_vex_statement_suppresses_it_as_it_would_a_feeds() {
        Vex vex = Vex.of(List.of(new VexStatement("CVE-2023-0286", List.of(), List.of("team/app"),
                VexStatus.NOT_AFFECTED, "vulnerable_code_not_in_execute_path", null,
                Instant.parse("2026-09-01T00:00:00Z"), "urn:acme:vex:image")));

        ComplianceGate.Assessment assessment = gate().vex(vex).bound(null, STORED).assess(IMAGE);

        assertThat(assessment.verdict()).isEqualTo(Verdict.ALLOW);
        assertThat(assessment.findings()).singleElement()
                .satisfies(finding -> assertThat(finding.detail()).contains("not applicable per VEX"));
    }

    @Test
    void unclaimed_content_does_not_ask_the_dimensions() {
        assertThat(gate().bound(null, STORED).assessUnclaimed(IMAGE).verdict()).isEqualTo(Verdict.ALLOW);
    }

    @Test
    void a_dimension_that_reads_only_the_subject_is_bound_to_itself() {
        GatePolicy plain = (subject, advisories) -> List.of();

        assertThat(plain.bound(null, STORED)).isSameAs(plain);
    }
}
