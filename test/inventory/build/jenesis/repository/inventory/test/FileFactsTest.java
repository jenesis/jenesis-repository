package build.jenesis.repository.inventory.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.inventory.DependencySection;
import build.jenesis.repository.inventory.ProvenanceSection;
import build.jenesis.repository.inventory.SignatureSection;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A version's dependencies, provenance and signature are its files' together: each file keeps its own, and what the
 * version says is derived from all of them, so it does not depend on which file landed last. A Maven release is the
 * shape - a POM declaring the dependencies and a jar declaring none, each signed on its own.
 */
class FileFactsTest {

    private static final String ECO = InventoryTestFormat.ECOSYSTEM;
    private static final String COORD = "com.example:lib";
    private static final String VERSION = "1.0.0";
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
    private static final String POM = "/maven/com/example/lib/1.0.0/lib-1.0.0.pom";
    private static final String JAR = "/maven/com/example/lib/1.0.0/lib-1.0.0.jar";

    @TempDir
    Path root;

    private ArtifactStore store;
    private MetadataStore metadata;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases");
        metadata = MetadataProvider.installed().over(store);
    }

    private StoreRepositoryInventory inventory() {
        return new StoreRepositoryInventory(store);
    }

    private Optional<Section> section(String tag) throws IOException {
        return metadata.section(ECO, COORD, VERSION, tag);
    }

    @Test
    void the_pom_s_dependencies_survive_the_jar_that_follows_it() throws IOException {
        inventory().recording(ECO, COORD, VERSION, false, NOW).file(POM)
                .dependencies(List.of(new DependencySection.Declared("org.base:base", "2.0"))).commit();
        inventory().recording(ECO, COORD, VERSION, false, NOW.plusSeconds(1)).file(JAR)
                .dependencies(List.of()).commit();

        assertThat(DependencySection.declared(section(DependencySection.TAG)).orElseThrow())
                .containsExactly(new DependencySection.Declared("org.base:base", "2.0"));
    }

    @Test
    void a_file_published_again_replaces_its_own_declarations_and_no_other() throws IOException {
        inventory().recording(ECO, COORD, VERSION, false, NOW).file(POM)
                .dependencies(List.of(new DependencySection.Declared("org.base:base", "2.0"))).commit();
        inventory().recording(ECO, COORD, VERSION, false, NOW).file(JAR)
                .dependencies(List.of(new DependencySection.Declared("org.shaded:inner", "1.0"))).commit();
        inventory().recording(ECO, COORD, VERSION, false, NOW).file(POM)
                .dependencies(List.of(new DependencySection.Declared("org.base:base", "2.1"))).commit();

        assertThat(DependencySection.declared(section(DependencySection.TAG)).orElseThrow())
                .containsExactlyInAnyOrder(new DependencySection.Declared("org.base:base", "2.1"),
                        new DependencySection.Declared("org.shaded:inner", "1.0"));
    }

    @Test
    void the_version_s_signature_is_its_least_trustworthy_file_s_whichever_landed_last() throws IOException {
        inventory().recording(ECO, COORD, VERSION, false, NOW).file(POM)
                .signature("ABSENT", null, null, null, null, null).commit();
        inventory().recording(ECO, COORD, VERSION, false, NOW.plusSeconds(1)).file(JAR)
                .signature("VALID", "openpgp:ABCD", "STRONG", JAR + ".asc", "configured", null).commit();

        SignatureSection.Summary version = SignatureSection.summary(section(SignatureSection.TAG)).orElseThrow();
        assertThat(version.outcome()).as("the unsigned POM is what the version is").isEqualTo("ABSENT");
        assertThat(section(SignatureSection.TAG).orElseThrow().signal().neutral()).isFalse();
        assertThat(SignatureSection.files(section(SignatureSection.TAG)))
                .as("each file keeps its own").containsOnlyKeys(POM, JAR)
                .hasEntrySatisfying(JAR, summary -> assertThat(summary.outcome()).isEqualTo("VALID"));

        inventory().recording(ECO, COORD, VERSION, false, NOW.plusSeconds(2)).file(POM)
                .signature("VALID", "openpgp:ABCD", "STRONG", POM + ".asc", "configured", null).commit();
        assertThat(SignatureSection.summary(section(SignatureSection.TAG)).orElseThrow().trusted())
                .as("once the POM's own signature arrives, every file is valid and so is the version").isTrue();
        assertThat(section(SignatureSection.TAG).orElseThrow().signal().neutral()).isTrue();
    }

    @Test
    void the_version_is_verified_only_when_every_attested_file_is() throws IOException {
        inventory().recording(ECO, COORD, VERSION, false, NOW).file(JAR).provenance(true, "a".repeat(64)).commit();
        inventory().recording(ECO, COORD, VERSION, false, NOW).file(POM).provenance(false, "b".repeat(64)).commit();

        ProvenanceSection.Summary summary = ProvenanceSection.summary(section(ProvenanceSection.TAG)).orElseThrow();
        assertThat(summary.verified()).isFalse();
        assertThat(summary.sha256()).as("named by the file that did not verify").isEqualTo("b".repeat(64));
    }

    @Test
    void a_file_s_facts_without_the_file_named_are_refused() {
        assertThatThrownBy(() -> inventory().recording(ECO, COORD, VERSION, false, NOW)
                .dependencies(List.of()).commit())
                .isInstanceOf(IllegalStateException.class);
    }
}
