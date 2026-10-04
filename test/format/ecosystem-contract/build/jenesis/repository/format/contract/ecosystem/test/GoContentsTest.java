package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.go.GoFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Go module version is its {@code .info}, {@code .mod} and {@code .zip}, each served from the key its path names, so
 * a copy of it - another deployment importing this one - carries what a go command reads before the archive.
 */
class GoContentsTest {

    @TempDir
    Path root;

    @Test
    void a_module_version_is_its_info_mod_and_zip_with_the_archive_last() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("default")
                .scope("default");
        Blobs blobs = new Blobs(store);
        for (String suffix : List.of(".info", ".mod", ".zip")) {
            blobs.write("go/example.com/hello/@v/v1.0.0" + suffix, ("the " + suffix).getBytes(StandardCharsets.UTF_8));
        }
        GoFormat go = new GoFormat();

        assertThat(go.contents("example.com/hello", "v1.0.0", store)).containsExactly(
                "/go/example.com/hello/@v/v1.0.0.info",
                "/go/example.com/hello/@v/v1.0.0.mod",
                "/go/example.com/hello/@v/v1.0.0.zip");
        for (String path : go.contents("example.com/hello", "v1.0.0", store)) {
            assertThat(go.servingKey(path, store)).as(path).hasValue(path.substring(1));
        }
        assertThat(go.servingKey("/go/example.com/hello/@v/list", store)).isEmpty();
        assertThat(go.contents("example.com/hello", "v2.0.0", store)).as("a version nothing serves").isEmpty();
    }
}
