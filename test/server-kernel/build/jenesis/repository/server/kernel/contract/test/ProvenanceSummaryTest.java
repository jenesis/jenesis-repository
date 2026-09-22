package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.inventory.ProvenanceSection;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The provenance summary section the accepted-publish path writes (§5.1/§6), over the real consolidated metadata
 * store: a verified summary is neutral, an unverified one carries the non-blocking WARNING signal, and both round-trip
 * as {@code {verified, sha256}}. Drives the {@link StoreRepositoryInventory#recordProvenance} seam
 * {@code ComplianceScreen} calls from the attestation verdict, so it exercises the codec, the signal mapping and the
 * document write end to end without booting the whole gate.
 */
class ProvenanceSummaryTest {

    private static final String ECO = "maven";
    private static final String COORD = "org.example:lib";
    private static final String VERSION = "1.0.0";
    private static final String SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    @Test
    void a_verified_provenance_summary_is_neutral() throws IOException {
        new StoreRepositoryInventory(store).recordProvenance(ECO, COORD, VERSION, true, SHA);

        MetadataStore metadata = MetadataProvider.installed().orElseThrow().over(store);
        Section section = metadata.section(ECO, COORD, VERSION, ProvenanceSection.TAG).orElseThrow();
        ProvenanceSection.Summary summary = ProvenanceSection.summary(Optional.of(section)).orElseThrow();
        assertThat(summary.verified()).as("the attestation verified and bound").isTrue();
        assertThat(summary.sha256()).isEqualTo(SHA);
        assertThat(section.signal().neutral()).as("a verified provenance summary is a neutral signal").isTrue();
    }

    @Test
    void an_unverified_provenance_summary_carries_a_non_blocking_warning() throws IOException {
        // The artifact was too large to hash whole (a null digest), so its binding could not be confirmed - the
        // gate admits it (some other policy may hold it, unchanged) but the summary flags provenance unconfirmed.
        new StoreRepositoryInventory(store).recordProvenance(ECO, COORD, VERSION, false, null);

        MetadataStore metadata = MetadataProvider.installed().orElseThrow().over(store);
        Section section = metadata.section(ECO, COORD, VERSION, ProvenanceSection.TAG).orElseThrow();
        ProvenanceSection.Summary summary = ProvenanceSection.summary(Optional.of(section)).orElseThrow();
        assertThat(summary.verified()).isFalse();
        assertThat(summary.sha256()).as("no digest bound").isNull();
        assertThat(section.signal().neutral()).as("an unverified summary is not neutral").isFalse();
        assertThat(section.signal().severity())
                .as("it is a non-blocking WARNING - LOW, below any 'reject HIGH and above' gate threshold")
                .isEqualTo(Severity.LOW);
    }

    @Test
    void the_summary_shares_the_document_with_the_other_sections() throws IOException {
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        inventory.record(ECO, COORD, VERSION, java.time.Instant.parse("2026-07-25T10:00:00Z"));
        inventory.recordProvenance(ECO, COORD, VERSION, true, SHA);

        MetadataStore metadata = MetadataProvider.installed().orElseThrow().over(store);
        assertThat(metadata.read(ECO, COORD, VERSION).orElseThrow().tags())
                .as("the provenance summary rides the same per-version document as the publish facts")
                .contains("published", ProvenanceSection.TAG);
    }
}
