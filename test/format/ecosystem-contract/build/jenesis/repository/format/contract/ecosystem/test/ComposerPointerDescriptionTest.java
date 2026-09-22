package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;

import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.store.ArtifactDescriptor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Composer key shapes the fixtured round trip cannot reach.
 *
 * <p>Composer is the case where counting segments is legitimate despite a coordinate that carries a slash, and the
 * reason is a fact about the ecosystem rather than about this store: a Composer coordinate is always exactly
 * {@code vendor/package}, so after the {@code index} marker there are exactly three segments. These state what
 * happens when a key does not have that shape - which is the only way the count could go wrong.
 */
class ComposerPointerDescriptionTest {

    private static final BlobLayout COMPOSER = new ComposerFormatFixture().layout();

    private static Optional<ArtifactDescriptor> describe(String key) {
        return COMPOSER.describePointer(key);
    }

    @Test
    void an_index_entry_names_its_vendor_package_and_version() {
        assertThat(describe("composer/packagist/index/acme/widget/1.2.3"))
                .hasValueSatisfying(named -> {
                    assertThat(named.coordinate()).isEqualTo("acme/widget");
                    assertThat(named.version()).isEqualTo("1.2.3");
                });
    }

    @Test
    void the_dist_archive_is_not_claimed() {
        // It would decode - the version is its own segment there too. Left alone deliberately: two ways to derive
        // one row are two ways for them to disagree, and the row is what retention ages by.
        assertThat(describe("composer/packagist/dist/acme/widget/1.2.3.zip")).isEmpty();
    }

    @Test
    void a_key_that_is_not_three_segments_past_the_marker_is_not_claimed() {
        assertThat(describe("composer/packagist/index/acme/widget")).isEmpty();        // the package's folder
        assertThat(describe("composer/packagist/index/acme")).isEmpty();               // the vendor's folder
        assertThat(describe("composer/packagist/index/acme/widget/1.2.3/extra")).isEmpty();
        assertThat(describe("composer/packagist/packages.json")).isEmpty();
        assertThat(describe("npm/left-pad/versions/1.0.0")).isEmpty();
    }

    @Test
    void a_traversal_shaped_key_is_not_claimed() {
        assertThat(describe("composer/packagist/index/../../secret/1.0.0")).isEmpty();
        assertThat(describe("composer/packagist/index/acme/widget/..")).isEmpty();
        assertThat(describe("composer/packagist/index//widget/1.0.0")).isEmpty();
    }
}
