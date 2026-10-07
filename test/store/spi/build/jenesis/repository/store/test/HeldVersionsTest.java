package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.HeldVersions;
import build.jenesis.repository.store.Publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The held versions of a package, read off the version face of the held-subject records: a version counts while the
 * review pointer of a path recorded for it is in place, the level is read past its first page, and a level holding
 * more names than the read answers for refuses rather than answering short.
 */
class HeldVersionsTest {

    private static final String PATH = "/maven/org/acme/lib/%s/lib-%s.jar";

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases");
    }

    @Test
    void a_version_counts_while_its_review_pointer_is_in_place_however_deep_in_the_level() throws IOException {
        for (int i = 0; i < 1_500; i++) {
            record(version(i));
        }
        String last = version(1_499);
        Publication publication = new Publication(store);
        String hash = publication.storeBlob(new ByteArrayInputStream("held".getBytes(StandardCharsets.UTF_8)));
        publication.link("/quarantine" + PATH.formatted(last, last), hash);

        assertThat(HeldVersions.of(store, "Maven", "org.acme:lib"))
                .as("the one version whose hold is in place, on the level's second page").containsExactly(last);
        assertThat(HeldVersions.all(store, "Maven")).containsOnlyKeys("org.acme:lib");

        publication.unpublish("/quarantine" + PATH.formatted(last, last));
        assertThat(HeldVersions.of(store, "Maven", "org.acme:lib"))
                .as("a row its hold no longer backs is a crash's leftover").isEmpty();
    }

    @Test
    void a_level_past_the_bound_refuses_rather_than_answering_short() throws IOException {
        for (int i = 0; i <= HeldVersions.MAX_VERSIONS; i++) {
            record(version(i));
        }
        assertThatThrownBy(() -> HeldVersions.of(store, "Maven", "org.acme:lib"))
                .isInstanceOf(IOException.class).hasMessageContaining("a short answer discloses a held version");
    }

    private void record(String version) throws IOException {
        store.write(HeldVersions.versionRoot("Maven", "org.acme:lib", version) + "/row",
                new ByteArrayInputStream(PATH.formatted(version, version).getBytes(StandardCharsets.UTF_8)));
    }

    private static String version(int i) {
        return String.format(Locale.ROOT, "1.%05d", i);
    }
}
