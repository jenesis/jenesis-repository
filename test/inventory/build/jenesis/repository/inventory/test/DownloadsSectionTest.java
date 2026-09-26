package build.jenesis.repository.inventory.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.inventory.DownloadsSection;
import build.jenesis.repository.inventory.LicenseSection;
import build.jenesis.repository.inventory.ProvenanceSection;
import build.jenesis.repository.inventory.PublishedSection;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code downloads} section: a delta-adding compare-and-set on the version's document that sums what every
 * flush - and every node - lands, keeps the newest download instant, and is what {@code lastDownloaded} reads first,
 * so the retention criterion and any surface see one fact, consolidated with the publish facts rather than kept as
 * a sidecar that a copy of the store would leave behind.
 */
class DownloadsSectionTest {

    private static final String ECO = "maven";
    private static final String COORD = "com.example:lib";
    private static final String VERSION = "1.0.0";
    private static final Instant T1 = Instant.parse("2026-05-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-05-01T06:00:00Z");

    @TempDir
    Path root;

    private ArtifactStore store;
    private MetadataStore metadata;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases");
        metadata = MetadataProvider.installed().over(store);
    }

    private StoreRepositoryInventory inventory() {
        return new StoreRepositoryInventory(store);
    }

    private DownloadsSection.Facts facts() throws IOException {
        return DownloadsSection.facts(metadata.section(ECO, COORD, VERSION, DownloadsSection.TAG)).orElseThrow();
    }

    @Test
    void deltas_sum_in_the_document_and_the_newest_instant_is_kept() throws IOException {
        assertThat(inventory().downloads(ECO, COORD, VERSION)).as("nothing recorded yet").isEmpty();
        assertThat(inventory().lastDownloaded(ECO, COORD, VERSION)).isEmpty();

        inventory().recordDownloads(ECO, COORD, VERSION, 3, T1);
        inventory().recordDownloads(ECO, COORD, VERSION, 2, T2);

        assertThat(facts().count()).as("two flushes, one from each interval, sum").isEqualTo(5);
        assertThat(facts().last()).as("the newest download instant").isEqualTo(T2);
        assertThat(inventory().downloads(ECO, COORD, VERSION)).contains(new DownloadsSection.Facts(5, T2));
        assertThat(inventory().lastDownloaded(ECO, COORD, VERSION)).as("read from the section").contains(T2);
    }

    @Test
    void an_older_delta_never_moves_the_newest_instant_back() throws IOException {
        inventory().recordDownloads(ECO, COORD, VERSION, 1, T2);
        inventory().recordDownloads(ECO, COORD, VERSION, 1, T1);   // a peer node flushing an older window

        assertThat(facts().count()).isEqualTo(2);
        assertThat(facts().last()).as("newest wins whatever the flush order").isEqualTo(T2);
    }

    @Test
    void the_section_sits_beside_the_publish_facts_in_one_document() throws IOException {
        inventory().record(ECO, COORD, VERSION, false, T1);
        inventory().recordDownloads(ECO, COORD, VERSION, 4, T2);

        assertThat(PublishedSection.published(metadata.section(ECO, COORD, VERSION, PublishedSection.TAG)))
                .as("recording downloads leaves the publish facts in place").isTrue();
        assertThat(facts().count()).isEqualTo(4);
        assertThat(inventory().release(ECO, COORD, VERSION).orElseThrow().lastDownloaded())
                .as("the release row reads its last download from the section").isEqualTo(T2);
    }
}
