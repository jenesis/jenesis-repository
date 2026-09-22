package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.huggingface.HuggingFaceImporter;
import build.jenesis.repository.store.ArtifactDescriptor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Hugging Face importer's target descriptor is derived from the resolve path, and a Nexus 3.71+ (H2/PostgreSQL
 * datastore) listing that reports the asset path absolute - with a leading slash - must resolve to the same coordinate
 * as the repository-relative shape, not a coordinate-breaking double slash ({@code /huggingface/huggingface//datasets/
 * …}). This is the container-free guard for the leading-slash normalisation the sibling Conda/Go importers already do;
 * the live-Nexus legs need Docker.
 */
class HuggingFaceImporterTest {

    @Test
    void a_relative_resolve_path_yields_the_repository_coordinate() {
        ArtifactDescriptor descriptor = new HuggingFaceImporter()
                .importTarget("datasets/acme/demo/resolve/main/data.bin")
                .orElseThrow();
        assertThat(descriptor.path()).doesNotContain("//");
    }

    @Test
    void an_absolute_h2_datastore_path_resolves_identically_not_to_a_double_slash() {
        ArtifactDescriptor relative = new HuggingFaceImporter()
                .importTarget("datasets/acme/demo/resolve/main/data.bin")
                .orElseThrow();
        ArtifactDescriptor absolute = new HuggingFaceImporter()
                .importTarget("/datasets/acme/demo/resolve/main/data.bin")
                .orElseThrow();

        assertThat(absolute.path()).as("the leading slash is normalised away, no double slash leaks into the path")
                .doesNotContain("//");
        assertThat(absolute.ecosystem()).isEqualTo(relative.ecosystem());
        assertThat(absolute.coordinate()).as("the absolute datastore path maps to the same coordinate")
                .isEqualTo(relative.coordinate());
        assertThat(absolute.version()).isEqualTo(relative.version());
        assertThat(absolute.path()).isEqualTo(relative.path());
    }

    @Test
    void a_non_resolve_asset_is_ignored() {
        assertThat(new HuggingFaceImporter().importTarget("api/models/acme/demo")).isEmpty();
        assertThat(new HuggingFaceImporter().importTarget("/api/models/acme/demo")).isEmpty();
    }
}
