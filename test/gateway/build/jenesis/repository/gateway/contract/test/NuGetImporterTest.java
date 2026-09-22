package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.nuget.NuGetImporter;
import build.jenesis.repository.store.ArtifactDescriptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The NuGet importer's target descriptor is derived from the flat-container {@code <id>/<version>/<file>.nupkg} shape a
 * live Nexus lays a package at, with the version bounded between the FIRST two path slashes rather than peeled from the
 * filename stem. A shape-assuming parse that took the {@code <id>.<version>.nupkg} filename stem as the version would
 * yield {@code newtonsoft.json.13.0.1}; keying on the version directory yields the true {@code 13.0.1}. Because the
 * version is a slash-bounded segment it can never carry a slash, and the id is lower-cased exactly as the store keys it,
 * so the incumbent deep asset path resolves to the real coordinate. A flat single-file {@code .nupkg} (no version
 * directory) is left to importArtifact, which replays it and reads the true coordinate from the embedded {@code .nuspec}.
 * Container-free - the live-Nexus legs need Docker.
 */
class NuGetImporterTest {

    @Test
    void derives_the_coordinate_from_a_flat_container_deep_path() {
        ArtifactDescriptor descriptor = new NuGetImporter()
                .importTarget("Newtonsoft.Json/13.0.1/newtonsoft.json.13.0.1.nupkg")
                .orElseThrow();
        assertThat(descriptor.ecosystem()).isEqualTo("NuGet");
        assertThat(descriptor.coordinate()).isEqualTo("newtonsoft.json");
        assertThat(descriptor.version()).isEqualTo("13.0.1");
    }

    @Test
    void bounds_the_version_by_the_directory_not_the_filename_stem() {
        // The distinguishing case: the filename stem is "serilog.2.10.0", but the true version is the directory segment
        // "2.10.0". A stem-peeling parse would report "serilog.2.10.0" (or worse, splice the .nupkg path in).
        ArtifactDescriptor descriptor = new NuGetImporter()
                .importTarget("Serilog/2.10.0/serilog.2.10.0.nupkg")
                .orElseThrow();
        assertThat(descriptor.coordinate()).isEqualTo("serilog");
        assertThat(descriptor.version()).isEqualTo("2.10.0");
        assertThat(descriptor.version()).doesNotContain("/");
        assertThat(descriptor.coordinate()).doesNotContain("/");
    }

    @Test
    void carries_a_prerelease_version() {
        ArtifactDescriptor descriptor = new NuGetImporter()
                .importTarget("Serilog/3.0.0-dev-00001/serilog.3.0.0-dev-00001.nupkg")
                .orElseThrow();
        assertThat(descriptor.coordinate()).isEqualTo("serilog");
        assertThat(descriptor.version()).isEqualTo("3.0.0-dev-00001");
        assertThat(descriptor.prerelease()).isTrue();
        assertThat(descriptor.version()).doesNotContain("/");
    }

    @Test
    void a_flat_single_file_path_is_left_to_replay() {
        // No <id>/<version>/ directory shape: the flat-container describe declines it, and importArtifact replays the
        // .nupkg and reads the true id/version from the embedded .nuspec instead of guessing from the flat filename.
        assertThat(new NuGetImporter().importTarget("newtonsoft.json.13.0.1.nupkg")).isEmpty();
    }

    @Test
    void a_non_package_asset_is_ignored() {
        // A directory-shaped row (a trailing slash, so an empty last segment) is refused BY NAME, not declined with
        // an empty answer: RepositoryImporter clause 3 reads empty as "this format screens elsewhere, lay the asset
        // out unscreened", which is the opposite of what a malformed path deserves. It was declined here before this
        // importer screened its source path through the shared RepositoryImporter.importablePath (the earlier retrofit).
        // A conforming source never reports one - ImportSource.safePath refuses exactly the same shapes - so this is
        // the belt behind that brace.
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new NuGetImporter().importTarget("Newtonsoft.Json/13.0.1/"));
        assertThat(new NuGetImporter().importTarget("v3/index.json")).isEmpty();
    }
}
