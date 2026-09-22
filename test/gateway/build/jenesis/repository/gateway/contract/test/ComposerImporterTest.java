package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.composer.ComposerImporter;
import build.jenesis.repository.store.ArtifactDescriptor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Composer importer's target descriptor is derived from the source path's TRAILING
 * {@code <vendor>/<package>/<version>.zip} segments - the exact three importArtifact keys the storage on - not by
 * splicing the whole raw source path in behind {@code dists/}. A live incumbent lays a dist at a deeper asset path (a
 * re-imported export nests it under its own {@code dists/}, a Nexus/Artifactory adds channel/store prefixes); the former
 * whole-path splice made {@code describe} see more than three segments after {@code dists/} and screen the real package
 * coordinate-less - degrading it to a raw blob on a later hold release even though importArtifact filed it correctly
 * from the same trailing segments. This is the container-free guard against that regression (the live-Nexus legs that
 * exercise the full walk need Docker).
 */
class ComposerImporterTest {

    @Test
    void derives_the_coordinate_from_a_deep_nexus_dist_path() {
        // A re-imported export lays the dist under its own dists/ prefix, so the source path is deeper than the bare
        // <vendor>/<package>/<version>.zip. A whole-path splice would strip one dists/ and see four segments -
        // "dists/monolog/monolog/2.9.1" - and screen it coordinate-less; the trailing-segment derivation resolves it.
        ArtifactDescriptor descriptor = new ComposerImporter()
                .importTarget("dists/monolog/monolog/2.9.1.zip")
                .orElseThrow();
        assertThat(descriptor.ecosystem()).isEqualTo("Packagist");
        assertThat(descriptor.coordinate()).isEqualTo("monolog/monolog");
        assertThat(descriptor.version()).isEqualTo("2.9.1");
    }

    @Test
    void derives_the_coordinate_from_a_nested_store_prefixed_path() {
        // A Nexus/Artifactory asset path carries store/channel prefix segments ahead of the coordinate; only the
        // trailing <vendor>/<package>/<version>.zip is the real coordinate.
        ArtifactDescriptor descriptor = new ComposerImporter()
                .importTarget("packages/psr/log/3.0.0.zip")
                .orElseThrow();
        assertThat(descriptor.coordinate()).isEqualTo("psr/log");
        assertThat(descriptor.version()).isEqualTo("3.0.0");
    }

    @Test
    void a_flat_coordinate_shaped_path_still_resolves() {
        ArtifactDescriptor descriptor = new ComposerImporter()
                .importTarget("symfony/console/6.3.0.zip")
                .orElseThrow();
        assertThat(descriptor.coordinate()).isEqualTo("symfony/console");
        assertThat(descriptor.version()).isEqualTo("6.3.0");
    }

    @Test
    void never_yields_a_slash_bearing_version() {
        new ComposerImporter().importTarget("dists/monolog/monolog/2.9.1.zip")
                .ifPresent(descriptor -> {
                    assertThat(descriptor.version()).doesNotContain("/");
                    // The coordinate is exactly vendor/package - one slash, never the source path's extra segments.
                    assertThat(descriptor.coordinate()).isEqualTo("monolog/monolog");
                });
    }

    @Test
    void a_non_distribution_asset_is_ignored() {
        assertThat(new ComposerImporter().importTarget("packages.json")).isEmpty();
        assertThat(new ComposerImporter().importTarget("p2/monolog/monolog.json")).isEmpty();
    }
}
