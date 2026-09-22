package build.jenesis.repository.inventory.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.inventory.LicenseInventory;
import build.jenesis.repository.inventory.LicenseSection;
import build.jenesis.repository.inventory.ProvenanceSection;
import build.jenesis.repository.inventory.SignatureSection;
import build.jenesis.repository.inventory.PublishedSection;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-coordinate rolled-up section views of the consolidated {@code meta} document (EPIC 20): the {@code published}
 * section carries the publish facts a pin preserves, the {@code provenance} section carries the attestation summary and
 * its non-blocking WARNING signal on an unverified verdict, and the {@code licenses} section carries the declared set a
 * sibling publish only ever unions into (never replaces), with the load-bearing absent-versus-present-but-empty
 * distinction and an order-independent rollup fingerprint. The sections are written through the inventory's own record
 * paths and read back through the discovered {@link MetadataStore}, the meta-doc double the metadata suites use.
 */
class MetaSectionsTest {

    private static final String ECO = InventoryTestFormat.ECOSYSTEM;
    private static final String COORD = "com.example:lib";
    private static final String VERSION = "1.0.0";
    private static final Instant NOW = Instant.parse("2026-05-01T00:00:00Z");

    @TempDir
    Path root;

    private ArtifactStore store;
    private MetadataStore metadata;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases");
        metadata = MetadataProvider.installed().orElseThrow().over(store);
    }

    private StoreRepositoryInventory inventory() {
        return new StoreRepositoryInventory(store);
    }

    private Optional<Section> section(String tag) throws IOException {
        return metadata.section(ECO, COORD, VERSION, tag);
    }

    @Test
    void the_published_section_carries_the_publish_facts_and_a_pin_preserves_them() throws IOException {
        inventory().record(ECO, COORD, VERSION, true, NOW);

        PublishedSection.Facts facts = PublishedSection.facts(section(PublishedSection.TAG)).orElseThrow();
        assertThat(PublishedSection.published(section(PublishedSection.TAG)))
                .as("a present published section is membership of the set").isTrue();
        assertThat(facts.at()).isEqualTo(NOW);
        assertThat(facts.prerelease()).isTrue();
        assertThat(facts.pinned()).isFalse();

        inventory().pin(ECO, COORD, VERSION);
        PublishedSection.Facts pinned = PublishedSection.facts(section(PublishedSection.TAG)).orElseThrow();
        assertThat(pinned.pinned()).as("the pin is set").isTrue();
        assertThat(pinned.at()).as("the pin preserves the publish instant").isEqualTo(NOW);
        assertThat(pinned.prerelease()).as("the pin preserves the prerelease flag").isTrue();

        inventory().unpin(ECO, COORD, VERSION);
        assertThat(PublishedSection.facts(section(PublishedSection.TAG)).orElseThrow().pinned())
                .as("the pin is cleared, the version returned to the retention rules").isFalse();
    }

    @Test
    void the_provenance_section_summarises_the_attestation_and_warns_when_unverified() throws IOException {
        inventory().recordProvenance(ECO, COORD, VERSION, false, "deadbeef");

        Optional<Section> unverified = section(ProvenanceSection.TAG);
        ProvenanceSection.Summary summary = ProvenanceSection.summary(unverified).orElseThrow();
        assertThat(summary.verified()).isFalse();
        assertThat(summary.sha256()).isEqualTo("deadbeef");
        assertThat(unverified.orElseThrow().signal().severity())
                .as("an unverified summary carries a non-blocking low-band WARNING signal").isEqualTo(Severity.LOW);

        inventory().recordProvenance(ECO, COORD, VERSION, true, "cafebabe");
        ProvenanceSection.Summary verified = ProvenanceSection.summary(section(ProvenanceSection.TAG)).orElseThrow();
        assertThat(verified.verified()).isTrue();
        assertThat(verified.sha256()).isEqualTo("cafebabe");
    }

    @Test
    void the_signature_section_keeps_the_source_and_what_the_material_stated() throws IOException {
        inventory().recording(ECO, COORD, VERSION, false, Instant.parse("2026-09-14T00:00:00Z"))
                .signature("VALID", "sigstore:https%3A%2F%2Fissuer|https%3A%2F%2Fgithub.com%2Facme%2Fwidget", "STRONG",
                        "/maven/widget.jar.sigstore.json", "provenance",
                        Map.of("issuer", "https://issuer", "subject", "https://github.com/acme/widget",
                                "log-index", "7", "integrated-time", "2026-09-14T00:00:01Z"))
                .commit();

        SignatureSection.Summary summary = SignatureSection.summary(section(SignatureSection.TAG)).orElseThrow();
        assertThat(summary.outcome()).isEqualTo("VALID");
        assertThat(summary.source()).isEqualTo("provenance");
        assertThat(summary.details()).containsEntry("issuer", "https://issuer")
                .containsEntry("subject", "https://github.com/acme/widget")
                .containsEntry("log-index", "7").containsEntry("integrated-time", "2026-09-14T00:00:01Z");

        inventory().recording(ECO, COORD, VERSION, false, Instant.parse("2026-09-14T00:00:00Z"))
                .signature("UNTRUSTED", "openpgp:ABCD", "STRONG", "/maven/widget.jar.asc", null, null).commit();
        SignatureSection.Summary bare = SignatureSection.summary(section(SignatureSection.TAG)).orElseThrow();
        assertThat(bare.source()).as("a signature nobody admitted names no source").isNull();
        assertThat(bare.details()).as("a scheme whose identity says everything states nothing apart").isEmpty();
    }

    @Test
    void the_licenses_section_unions_declarations_rather_than_replacing_them() throws IOException {
        LicenseInventory licenses = new LicenseInventory(store);
        licenses.record(ECO, COORD, VERSION, List.of(
                new LicenseInventory.Declared("Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0")));
        licenses.record(ECO, COORD, VERSION, List.of(new LicenseInventory.Declared("MIT", null)));

        // A sibling publish adds to the declared set; it never launders the first license out.
        assertThat(licenses.read(ECO, COORD, VERSION).orElseThrow())
                .extracting(LicenseInventory.Declared::name)
                .containsExactlyInAnyOrder("Apache License 2.0", "MIT");
        // The same union is what the licenses section carries in the document.
        assertThat(LicenseSection.declared(section(LicenseSection.TAG)))
                .extracting(LicenseInventory.Declared::name)
                .containsExactlyInAnyOrder("Apache License 2.0", "MIT");
    }

    @Test
    void an_absent_license_record_is_distinct_from_a_present_but_empty_one() throws IOException {
        LicenseInventory licenses = new LicenseInventory(store);
        assertThat(licenses.read(ECO, COORD, VERSION)).as("never inspected: absent").isEmpty();

        licenses.record(ECO, COORD, VERSION, List.of());
        assertThat(licenses.read(ECO, COORD, VERSION))
                .as("inspected, none declared: present-but-empty, not absent").isPresent()
                .get().asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(LicenseInventory.Declared.class))
                .isEmpty();
    }

    @Test
    void the_license_fingerprint_is_order_independent_and_keeps_the_absent_distinction() {
        LicenseInventory.Declared apache =
                new LicenseInventory.Declared("Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0");
        LicenseInventory.Declared mit = new LicenseInventory.Declared("MIT", null);

        Optional<byte[]> forward = LicenseSection.fingerprintOf(Optional.of(List.of(apache, mit)));
        Optional<byte[]> reversed = LicenseSection.fingerprintOf(Optional.of(List.of(mit, apache)));
        assertThat(forward).isPresent();
        assertThat(Arrays.equals(forward.orElseThrow(), reversed.orElseThrow()))
                .as("the fingerprint is a canonical encoding of the SET, independent of union order").isTrue();

        assertThat(LicenseSection.fingerprintOf(Optional.empty()))
                .as("an absent set has no fingerprint").isEmpty();
        assertThat(LicenseSection.fingerprintOf(Optional.of(List.of())))
                .as("a present-but-empty set still fingerprints, keeping it distinct from absent").isPresent();
    }
}
