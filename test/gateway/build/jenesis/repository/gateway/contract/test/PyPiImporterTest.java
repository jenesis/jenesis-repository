package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.pypi.PyPiImporter;
import build.jenesis.repository.store.ArtifactDescriptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The PyPI importer's target descriptor is derived from the distribution filename, not the raw source path, so a
 * deep incumbent-manager asset path (a live Nexus lays a distribution at {@code packages/<name>/<version>/<file>})
 * resolves to the true PyPI coordinate rather than a version segment carrying the path's slashes. A slash-bearing
 * version was rejected downstream as {@code Not a traversal-free scope segment}, failing the whole import - this is
 * the container-free guard against that regression (the live-Nexus legs that first surfaced it need Docker).
 */
class PyPiImporterTest {

    @Test
    void derives_the_coordinate_from_a_wheel_filename_on_a_deep_nexus_path() {
        ArtifactDescriptor descriptor = new PyPiImporter()
                .importTarget("packages/acme-demo/1.0.0/acme_demo-1.0.0-py3-none-any.whl")
                .orElseThrow();
        assertThat(descriptor.ecosystem()).isEqualTo("PyPI");
        assertThat(descriptor.coordinate()).isEqualTo("acme-demo");
        assertThat(descriptor.version()).isEqualTo("1.0.0");
    }

    @Test
    void derives_the_coordinate_from_an_sdist_filename_on_a_deep_nexus_path() {
        ArtifactDescriptor descriptor = new PyPiImporter()
                .importTarget("packages/source/j/acme-demo/acme-demo-2.3.4.tar.gz")
                .orElseThrow();
        assertThat(descriptor.coordinate()).isEqualTo("acme-demo");
        assertThat(descriptor.version()).isEqualTo("2.3.4");
    }

    @Test
    void never_yields_a_slash_bearing_version() {
        // The exact defect: the raw path leaked into the version, tripping ArtifactStore.segment downstream.
        new PyPiImporter().importTarget("packages/acme-demo/1.0.0/acme_demo-1.0.0-py3-none-any.whl")
                .ifPresent(descriptor -> {
                    assertThat(descriptor.version()).doesNotContain("/");
                    assertThat(descriptor.coordinate()).doesNotContain("/");
                });
    }

    @Test
    void a_flat_two_segment_path_still_resolves() {
        ArtifactDescriptor descriptor = new PyPiImporter()
                .importTarget("acme_demo-1.0.0-py3-none-any.whl")
                .orElseThrow();
        assertThat(descriptor.coordinate()).isEqualTo("acme-demo");
        assertThat(descriptor.version()).isEqualTo("1.0.0");
    }

    @Test
    void a_non_distribution_asset_is_ignored() {
        // A directory-shaped row (a trailing slash, so an empty last segment) is refused BY NAME, not declined with
        // an empty answer: RepositoryImporter clause 3 reads empty as "this format screens elsewhere, lay the asset
        // out unscreened", which is the opposite of what a malformed path deserves. It was declined here before this
        // importer screened its source path through the shared RepositoryImporter.importablePath.
        // A conforming source never reports one - ImportSource.safePath refuses exactly the same shapes - so this is
        // the belt behind that brace.
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new PyPiImporter().importTarget("packages/acme-demo/1.0.0/"));
        assertThat(new PyPiImporter().importTarget("simple/acme-demo/index.html")).isEmpty();
    }
}
