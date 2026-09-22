package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.cocoapods.CocoaPodsImporter;
import build.jenesis.repository.store.ArtifactDescriptor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CocoaPods importer's target descriptor is derived from the source path's TRAILING
 * {@code <name>/<version>/<file>.zip} segments - the exact ones importArtifact keys the storage on - and the canonical
 * served {@code pods/} path is rebuilt from them, not by splicing the whole raw source path in behind the registry. A
 * live incumbent lays a pod at a bare {@code <name>/<version>/<file>.zip} (no {@code pods/} segment) or a deeper asset
 * path; the former whole-path splice reached {@code describe}, which recognises only a {@code pods/}-rooted download, so
 * a non-{@code pods/} incumbent path screened the real pod coordinate-less/empty - degrading it to a raw blob on a later
 * hold release even though importArtifact filed it correctly from the same trailing segments. This is the container-free
 * guard against that regression (the live-Nexus legs that exercise the full walk need Docker).
 */
class CocoaPodsImporterTest {

    @Test
    void derives_the_coordinate_from_a_bare_non_pods_incumbent_path() {
        // The defect shape: a real incumbent stores the archive at <name>/<version>/<file>.zip with no pods/ segment.
        // Splicing that straight in produced a path describe did not recognise as a pods/ download, so the screen was
        // empty while importArtifact still filed the pod - the release-degradation regression. Rebuilding the canonical
        // pods/ path from the trailing segments resolves it.
        ArtifactDescriptor descriptor = new CocoaPodsImporter()
                .importTarget("Alamofire/5.6.4/Alamofire.zip")
                .orElseThrow();
        assertThat(descriptor.ecosystem()).isEqualTo("CocoaPods");
        assertThat(descriptor.coordinate()).isEqualTo("Alamofire");
        assertThat(descriptor.version()).isEqualTo("5.6.4");
    }

    @Test
    void derives_the_coordinate_from_a_deep_nexus_path() {
        // A Nexus/Artifactory asset path carries store/channel prefix segments ahead of the coordinate; only the
        // trailing <name>/<version>/<file>.zip is the real coordinate.
        ArtifactDescriptor descriptor = new CocoaPodsImporter()
                .importTarget("cocoapods/pods/SnapKit/5.6.0/SnapKit.zip")
                .orElseThrow();
        assertThat(descriptor.coordinate()).isEqualTo("SnapKit");
        assertThat(descriptor.version()).isEqualTo("5.6.0");
    }

    @Test
    void a_pods_rooted_download_shaped_path_still_resolves() {
        ArtifactDescriptor descriptor = new CocoaPodsImporter()
                .importTarget("pods/Alamofire/5.6.4/Alamofire.zip")
                .orElseThrow();
        assertThat(descriptor.coordinate()).isEqualTo("Alamofire");
        assertThat(descriptor.version()).isEqualTo("5.6.4");
    }

    @Test
    void never_yields_a_slash_bearing_version() {
        new CocoaPodsImporter().importTarget("Alamofire/5.6.4/Alamofire.zip")
                .ifPresent(descriptor -> {
                    assertThat(descriptor.version()).doesNotContain("/");
                    assertThat(descriptor.coordinate()).doesNotContain("/");
                });
    }

    @Test
    void a_non_distribution_asset_is_ignored() {
        assertThat(new CocoaPodsImporter().importTarget("all_pods_versions_a_1_2.txt")).isEmpty();
        assertThat(new CocoaPodsImporter()
                .importTarget("Specs/a/1/2/Alamofire/5.6.4/Alamofire.podspec.json")).isEmpty();
    }
}
