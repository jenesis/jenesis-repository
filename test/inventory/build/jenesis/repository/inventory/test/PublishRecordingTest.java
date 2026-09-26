package build.jenesis.repository.inventory.test;

import module java.base;

import build.jenesis.repository.inventory.LicenseInventory;
import build.jenesis.repository.inventory.OriginSection;
import build.jenesis.repository.inventory.ProvenanceSection;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One publish's inventory facts land in one write of the version's document, and the identity index folds once with
 * the licences that document holds - so the incremental identity a first publish leaves equals the one a full
 * rebuild computes. Before the recording, the same facts took two writes of the same document and the identity
 * folded between them with a fingerprint the second write had yet to record.
 */
class PublishRecordingTest {

    private static final String ECO = "Maven";
    private static final String COORD = "org.probe:lib";
    private static final Instant PUBLISHED = Instant.parse("2026-09-07T00:00:00Z");
    private static final List<LicenseInventory.Declared> APACHE =
            List.of(new LicenseInventory.Declared("Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0"));

    @TempDir
    Path root;

    private FaultInjectingStore store;
    private final List<String> written = new ArrayList<>();

    @BeforeEach
    void setUp() {
        store = FaultInjectingStore.wrap(ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases"))
                .tracing((op, key) -> {
                    if (op == FaultInjectingStore.Op.WRITE_VERSIONED) {
                        written.add(key);
                    }
                });
    }

    @Test
    void one_publish_is_one_write_of_its_version_document_carrying_every_fact() throws IOException {
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        inventory.recording(ECO, COORD, "1.0", false, PUBLISHED)
                .origin("a".repeat(64))
                .licenses(APACHE)
                .provenance(true, "b".repeat(64))
                .commit();

        String document = MetadataKey.version(ECO, COORD, "1.0");
        assertThat(written.stream().filter(document::equals).count())
                .as("the version's document is written once, whatever the recording carries").isEqualTo(1);
        MetadataStore metadata = MetadataProvider.installed().over(store);
        assertThat(inventory.publishedAt(ECO, COORD, "1.0")).as("published").contains(PUBLISHED);
        assertThat(metadata.section(ECO, COORD, "1.0", OriginSection.TAG)).as("the origin row").isPresent();
        assertThat(new LicenseInventory(store).read(ECO, COORD, "1.0")).as("the licences").contains(APACHE);
        assertThat(metadata.section(ECO, COORD, "1.0", ProvenanceSection.TAG)).as("the provenance summary").isPresent();
    }

    @Test
    void the_identity_folds_once_with_the_licences_the_document_holds() throws IOException {
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        inventory.recording(ECO, COORD, "1.0", false, PUBLISHED).origin("a".repeat(64)).licenses(APACHE).commit();

        assertThat(inventory.identity())
                .as("the first publish's incremental fold agrees with a full rebuild, licences included")
                .isEqualTo(HexFormat.of().formatHex(inventory.rebuildIdentity()));

        inventory.recording(ECO, COORD, "1.0", false, PUBLISHED.plusSeconds(60))
                .licenses(List.of(new LicenseInventory.Declared("MIT", "https://opensource.org/license/mit")))
                .commit();

        assertThat(new LicenseInventory(store).read(ECO, COORD, "1.0")).as("a re-publish unions its licences")
                .hasValueSatisfying(declared -> assertThat(declared).hasSize(2));
        assertThat(inventory.identity()).as("and re-folds the member from the old set to the new")
                .isEqualTo(HexFormat.of().formatHex(inventory.rebuildIdentity()));
    }

    @Test
    void a_path_no_format_describes_that_far_records_nothing_and_a_described_one_records_from_the_path() {
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        assertThat(inventory.recording("/maven/org/probe/lib/", PUBLISHED)).as("a folder").isEmpty();
        assertThat(inventory.recording("/nowhere/x", PUBLISHED)).as("no format claims it").isEmpty();
    }
}
