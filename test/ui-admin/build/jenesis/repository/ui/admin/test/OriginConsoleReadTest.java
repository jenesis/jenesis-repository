package build.jenesis.repository.ui.admin.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.inventory.OriginSection;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.ui.store.RepositoryBrowse;
import io.micrometer.observation.ObservationRegistry;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The origin panel at the model level: the console reads the {@code origin} acquisition rows ({@link OriginSection})
 * of a published coordinate version from its consolidated metadata document and surfaces them on the artifact detail and
 * through the origin API - a small bounded read of the one section, never the artifact body. A local-upload row reads as
 * "uploaded"; a fallback row reads as "via fallback X" with its store/screen policy and no-copy {@code serves} counter.
 * Empty when nothing has been recorded (render-what-you-have). Sibling of {@code RepositoryBrowseTest}.
 */
public class OriginConsoleReadTest {

    private static final String PATH = "/maven/org/acme/lib/1.0/lib-1.0.jar";

    @TempDir
    Path root;

    private RepositoryBrowse browse;
    private ArtifactStore repo;

    @BeforeEach
    void setUp() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        browse = new RepositoryBrowse(store, new CurrentTenant() {
            @Override
            public String name() {
                return "acme";
            }
        }, ObservationRegistry.NOOP);
        repo = store.scope("acme").scope("central");
        Publication publication = new Publication(repo);
        publication.link(PATH, publication.storeBlob(new ByteArrayInputStream("a library jar".getBytes(UTF_8))));
        new StoreRepositoryInventory(repo).record("Maven", "org.acme:lib", "1.0",
                Instant.parse("2026-06-01T00:00:00Z"));
    }

    @Test
    void a_path_with_no_recorded_origin_reads_as_empty() throws IOException {
        assertThat(browse.origin("central", PATH)).as("nothing recorded yet").isEmpty();
    }

    @Test
    void an_uploaded_and_a_fallback_acquisition_read_back_as_origin_rows() throws IOException {
        // The coordinate the maven layout describes for the path - the key the origin section is written under, aligned
        // with what browse.origin resolves it to.
        RepositoryBrowse.ArtifactDetail detail = browse.artifact("central", PATH);
        MetadataStore metadata = MetadataProvider.installed().over(repo);
        metadata.mutate(detail.ecosystem(), detail.coordinate(), detail.version(), OriginSection.TAG,
                OriginSection.recordUpload("upload-sha", Instant.parse("2026-06-01T00:00:00Z")));
        metadata.mutate(detail.ecosystem(), detail.coordinate(), detail.version(), OriginSection.TAG,
                OriginSection.recordFallback("central", 0, "https://repo1.maven.org/maven2/org/acme/lib/1.0/lib-1.0.jar",
                        "fetch-sha", false, "harden", Instant.parse("2026-06-02T00:00:00Z")));
        // A second serve of the SAME fallback bytes bumps serves/lastServed rather than appending a row.
        metadata.mutate(detail.ecosystem(), detail.coordinate(), detail.version(), OriginSection.TAG,
                OriginSection.recordFallback("central", 0, "https://repo1.maven.org/maven2/org/acme/lib/1.0/lib-1.0.jar",
                        "fetch-sha", false, "harden", Instant.parse("2026-06-03T00:00:00Z")));

        List<RepositoryBrowse.OriginRow> origin = browse.origin("central", PATH);
        assertThat(origin).hasSize(2);
        assertThat(origin).anySatisfy(row -> {
            assertThat(row.upload()).isTrue();
            assertThat(row.sha256()).isEqualTo("upload-sha");
        });
        assertThat(origin).anySatisfy(row -> {
            assertThat(row.fallback()).isTrue();
            assertThat(row.repository()).isEqualTo("central");
            assertThat(row.fallbackIndex()).isZero();
            assertThat(row.target()).contains("repo1.maven.org");
            assertThat(row.sha256()).isEqualTo("fetch-sha");
            assertThat(row.stored()).as("a pass-through fallback").isFalse();
            assertThat(row.screening()).isEqualTo("harden");
            assertThat(row.serves()).as("two serves of the same bytes coalesced").isEqualTo(2L);
        });
    }
}
