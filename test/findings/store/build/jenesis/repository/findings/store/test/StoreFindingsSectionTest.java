package build.jenesis.repository.findings.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.findings.Finding;
import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.findings.store.StoreFindings;
import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The store-backed findings ledger over the consolidated metadata document's {@code findings} section: a recorded
 * finding lands in the document; the section's signal summarises the highest severity; a sibling section on the same
 * coordinate survives a findings mutate; an unrecognised (newer-node) section row rides a mutate verbatim; and the
 * batched {@code recordAll}/{@code commit} fold a whole pass into one write.
 */
class StoreFindingsSectionTest {

    private static final Instant FIRST = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-07-05T00:00:00Z");

    @TempDir
    Path root;

    private ArtifactStore store;
    private MetadataStore metadata;
    private StoreFindings findings;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        metadata = MetadataProvider.installed().over(store);
        findings = new StoreFindings(store, metadata);
    }

    @Test
    void a_recorded_finding_lands_in_the_documents_findings_section() throws IOException {
        findings.record("Maven", "org.acme:lib", "1.0", Finding.of(
                "CVE-1", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.HIGH, "vuln", FIRST));

        assertThat(findings.of("Maven", "org.acme:lib", "1.0")).singleElement()
                .satisfies(finding -> assertThat(finding.id()).isEqualTo("CVE-1"));
        assertThat(metadata.section("Maven", "org.acme:lib", "1.0", "findings"))
                .as("the rows live in the document's findings section").isPresent()
                .get().satisfies(section -> assertThat(section.signal().severity())
                        .as("the section signal summarises the highest active severity for the gate/renderer")
                        .isEqualTo(Severity.HIGH));
    }

    @Test
    void the_findings_section_coexists_with_a_sibling_section_on_the_same_document() throws IOException {
        // A licenses/publish-style sibling section on the same coordinate: a findings mutate must carry it verbatim.
        metadata.mutate("Maven", "org.acme:lib", "1.0", "published", current -> Section.empty("published", 1, FIRST));
        findings.record("Maven", "org.acme:lib", "1.0", Finding.of(
                "CVE-1", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.HIGH, "vuln", FIRST));

        assertThat(metadata.read("Maven", "org.acme:lib", "1.0")).get()
                .satisfies(document -> assertThat(document.tags()).containsExactlyInAnyOrder("published", "findings"));
        assertThat(findings.of("Maven", "org.acme:lib", "1.0")).hasSize(1);
        assertThat(metadata.section("Maven", "org.acme:lib", "1.0", "published"))
                .as("the sibling section survives the findings write").isPresent();
    }

    @Test
    void an_unrecognised_section_row_rides_a_record_through_verbatim() throws IOException {
        // A newer node wrote this coordinate's findings section holding a row this node fully understands beside one it
        // cannot (a Finding.Kind/Severity newer than its enums). The record must not drop the row it cannot parse.
        String futureRow = "{\"id\":\"SBOM-TAMPER-1\",\"source\":\"provenance-engine\","
                + "\"kind\":\"SUPPLY_CHAIN_INTEGRITY\",\"category\":\"attestation\",\"severity\":\"CATACLYSMIC\","
                + "\"confidence\":0.95,\"description\":\"unsigned rebuild from the future\",\"references\":[],"
                + "\"provenance\":\"newer-node\",\"attributes\":{},\"firstSeen\":\"2026-07-10T00:00:00Z\","
                + "\"lastSeen\":\"2026-07-10T00:00:00Z\",\"labels\":[]}";
        String documentBytes = "{\"format\":1,\"sections\":{\"findings\":{\"schema\":1,"
                + "\"updated\":\"2026-07-01T00:00:00Z\",\"state\":\"derived\",\"data\":{\"findings\":["
                + row("CVE-OLD", "osv", "VULNERABILITY", "MEDIUM", "known here") + "," + futureRow + "]}}}}";
        store.writeVersioned(MetadataKey.version("Maven", "org.acme:lib", "1.0"),
                documentBytes.getBytes(StandardCharsets.UTF_8), null);

        findings.record("Maven", "org.acme:lib", "1.0", Finding.of(
                "CVE-OLD", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.HIGH, "rescored", LATER));
        findings.record("Maven", "org.acme:lib", "1.0", Finding.of(
                "CVE-NEW", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.LOW, "new here", LATER));

        assertThat(findings.of("Maven", "org.acme:lib", "1.0"))
                .as("a reader never sees the kind/severity it cannot parse")
                .extracting(Finding::id).containsExactly("CVE-OLD", "CVE-NEW");
        String persisted = new String(store.readVersioned(MetadataKey.version("Maven", "org.acme:lib", "1.0"))
                .orElseThrow().content(), StandardCharsets.UTF_8);
        assertThat(persisted).as("the future row rode the section mutate through untouched")
                .contains("SBOM-TAMPER-1").contains("SUPPLY_CHAIN_INTEGRITY").contains("CATACLYSMIC");
    }

    @Test
    void a_batched_record_all_folds_a_whole_pass_into_one_union() throws IOException {
        findings.recordAll("Maven", "org.acme:lib", "1.0", List.of(
                Finding.of("CVE-1", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.HIGH, "a", FIRST),
                Finding.of("CVE-2", "github", Finding.Kind.VULNERABILITY, "advisory", Severity.CRITICAL, "b", FIRST),
                Finding.of("MAL-1", "openssf", Finding.Kind.MALWARE, "advisory", Severity.HIGH, "c", FIRST)));

        assertThat(findings.of("Maven", "org.acme:lib", "1.0")).hasSize(3);
        // Idempotent: re-recording refreshes rather than duplicating.
        findings.recordAll("Maven", "org.acme:lib", "1.0", List.of(
                Finding.of("CVE-1", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.LOW, "rescored", LATER)));
        assertThat(findings.of("Maven", "org.acme:lib", "1.0")).hasSize(3);
        assertThat(findings.of("Maven", "org.acme:lib", "1.0"))
                .filteredOn(finding -> finding.id().equals("CVE-1")).singleElement()
                .satisfies(finding -> assertThat(finding.severity()).isEqualTo(Severity.LOW));
    }

    @Test
    void a_batched_commit_records_and_labels_together_in_one_pass() throws IOException {
        findings.record("Maven", "org.acme:lib", "1.0", Finding.of(
                "CVE-1", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.HIGH, "vuln", FIRST));

        // The AI-sweep shape: a queryable judgement row plus labels on the advisory row, in one commit.
        findings.commit("Maven", "org.acme:lib", "1.0", new Findings.Batch(
                List.of(Finding.of("APP-1", "ai-applicability", Finding.Kind.APPLICABILITY, "applies", Severity.NONE,
                        "applies in context", FIRST)),
                List.of(new Findings.Batch.Annotation("osv", "CVE-1",
                        new Finding.Label("ai", "applicability", "applies", 0.9, FIRST)))));

        assertThat(findings.of("Maven", "org.acme:lib", "1.0")).hasSize(2);
        assertThat(findings.of("Maven", "org.acme:lib", "1.0"))
                .filteredOn(finding -> finding.id().equals("CVE-1")).singleElement()
                .satisfies(finding -> assertThat(finding.labels()).singleElement()
                        .satisfies(label -> assertThat(label.value()).isEqualTo("applies")));
    }

    @Test
    void a_batched_label_of_a_missing_row_throws() {
        assertThatThrownBy(() -> findings.labelAll("Maven", "org.acme:lib", "1.0",
                List.of(new Findings.Batch.Annotation("osv", "ABSENT",
                        new Finding.Label("ai", "applicability", "applies", 1.0, FIRST)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] document(String... rows) {
        return ("{\"findings\":[" + String.join(",", rows) + "]}").getBytes(StandardCharsets.UTF_8);
    }

    private static String row(String id, String source, String kind, String severity, String description) {
        return "{\"id\":\"" + id + "\",\"source\":\"" + source + "\",\"kind\":\"" + kind + "\","
                + "\"category\":\"advisory\",\"severity\":\"" + severity + "\",\"confidence\":1.0,"
                + "\"description\":\"" + description + "\",\"references\":[],\"provenance\":\"\",\"attributes\":{},"
                + "\"firstSeen\":\"2026-07-01T00:00:00Z\",\"lastSeen\":\"2026-07-01T00:00:00Z\",\"labels\":[]}";
    }
}
