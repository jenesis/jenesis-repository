package build.jenesis.repository.gate.reported.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.compliance.VulnerabilityPolicy;
import build.jenesis.repository.findings.Finding;
import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.findings.FindingsProvider;
import build.jenesis.repository.findings.WaiverLabels;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.gate.store.GatedRepository;
import build.jenesis.repository.gate.store.ReportedFindings;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A scanner's findings about a version already served, over a real store: recorded under the scanner's name, decided
 * by the gate a publish meets, and - when the gate would not admit them - withheld onto the review queue, from which
 * the ordinary release serves the version again.
 */
class ReportedFindingsTest {

    private static final String PATH = "/maven/org/example/app/1.0/app-1.0.jar";
    private static final String ECOSYSTEM = "Maven", COORDINATE = "org.example:app", VERSION = "1.0";
    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    /** The gate at the shipped threshold, refusing at publish; a later hold is a hold whatever the action. */
    private static final ComplianceGate GATE =
            new ComplianceGate(new VulnerabilityPolicy(Severity.CRITICAL).action(Verdict.REJECT), AdvisorySource.none());

    @TempDir
    Path root;

    private ArtifactStore store;
    private Findings ledger;

    @BeforeEach
    void publish() throws IOException {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases");
        Publication publication = new Publication(store);
        publication.link(PATH, publication.storeBlob(
                new ByteArrayInputStream("application".getBytes(StandardCharsets.UTF_8))));
        new StoreRepositoryInventory(store).record(PATH, NOW);
        ledger = FindingsProvider.installed().orElseThrow().over(store);
    }

    @Test
    void a_critical_report_is_recorded_under_the_scanner_and_withholds_the_version_until_released()
            throws IOException {
        ReportedFindings.Outcome outcome = apply(report(new AdvisorySource.Advisory(
                "CVE-2024-0001", Severity.CRITICAL, false, "1.1", List.of("CVE-2024-0001"), "openssl in the base layer")));

        assertThat(outcome.verdict()).isEqualTo(Verdict.REJECT);
        assertThat(outcome.held()).as("a served version cannot be refused, only withdrawn for review").isTrue();
        assertThat(held()).isTrue();
        assertThat(new QuarantineLog(store).events()).singleElement().satisfies(event -> {
            assertThat(event.verdict()).isEqualTo(Verdict.QUARANTINE);
            assertThat(event.reasons()).singleElement().asString()
                    .startsWith("Reported by trivy:").contains("CVE-2024-0001");
        });
        assertThat(ledger.of(ECOSYSTEM, COORDINATE, VERSION)).singleElement().satisfies(finding -> {
            assertThat(finding.source()).as("attributed to the scanner that reported it").isEqualTo("trivy");
            assertThat(finding.provenance()).isEqualTo(ReportedFindings.PROVENANCE);
            assertThat(finding.kind()).isEqualTo(Finding.Kind.VULNERABILITY);
        });

        new GatedRepository(store).release(PATH);

        assertThat(held()).as("the ordinary release clears a reported hold").isFalse();
    }

    @Test
    void a_report_below_the_threshold_is_recorded_and_withholds_nothing() throws IOException {
        ReportedFindings.Outcome outcome = apply(report(new AdvisorySource.Advisory("CVE-2024-0002", Severity.LOW)));

        assertThat(outcome.verdict()).isEqualTo(Verdict.ALLOW);
        assertThat(outcome.held()).isFalse();
        assertThat(held()).isFalse();
        assertThat(ledger.of(ECOSYSTEM, COORDINATE, VERSION)).hasSize(1);
    }

    @Test
    void a_waived_finding_is_honoured_as_it_is_for_a_feed() throws IOException {
        AdvisorySource.Advisory critical = new AdvisorySource.Advisory("CVE-2024-0003", Severity.CRITICAL);
        apply(report(new AdvisorySource.Advisory("CVE-2024-0003", Severity.LOW)));
        WaiverLabels.apply(ledger, ECOSYSTEM, COORDINATE, VERSION, "trivy", "CVE-2024-0003",
                NOW.plus(Duration.ofDays(30)), "accepted until the base image moves", NOW);

        ReportedFindings.Outcome outcome = apply(report(critical));

        assertThat(outcome.verdict()).as("the operator's accepted risk stands for a reported finding too")
                .isEqualTo(Verdict.ALLOW);
        assertThat(held()).isFalse();
    }

    @Test
    void a_report_about_a_version_this_repository_does_not_serve_changes_nothing() throws IOException {
        assertThat(ReportedFindings.apply(store, ledger, GATE, new ReportedFindings.Report("trivy", ECOSYSTEM,
                COORDINATE, "9.9", List.of(new AdvisorySource.Advisory("CVE-2024-0004", Severity.CRITICAL))), NOW))
                .isEmpty();
        assertThat(ledger.of(ECOSYSTEM, COORDINATE, "9.9")).isEmpty();
    }

    private static ReportedFindings.Report report(AdvisorySource.Advisory advisory) {
        return new ReportedFindings.Report("trivy", ECOSYSTEM, COORDINATE, VERSION, List.of(advisory));
    }

    private ReportedFindings.Outcome apply(ReportedFindings.Report report) throws IOException {
        return ReportedFindings.apply(store, ledger, GATE, report, NOW).orElseThrow();
    }

    private boolean held() throws IOException {
        return store.readVersioned(Publication.quarantineKey(PATH)).isPresent();
    }
}
