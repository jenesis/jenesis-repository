package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;

import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.store.ArtifactDescriptor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Go key shapes the fixtured round trip cannot reach.
 *
 * <p>Go is the case where the coordinate and the version genuinely cannot be separated by counting segments: a
 * module path is multi-segment by design. They are separated by {@code /@v/}, and the reason that is safe rather
 * than merely convenient is worth a test of its own - {@code @v} is the module proxy protocol's reserved
 * separator, so a module path cannot contain one.
 */
class GoPointerDescriptionTest {

    private static final BlobLayout GO = new GoFormatFixture().layout();

    private static Optional<ArtifactDescriptor> describe(String key) {
        return GO.describePointer(key);
    }

    @Test
    void a_multi_segment_module_path_keeps_all_of_its_segments() {
        assertThat(describe("go/github.com/acme/widget/@v/v1.2.3.info"))
                .hasValueSatisfying(named -> {
                    assertThat(named.coordinate()).isEqualTo("github.com/acme/widget");
                    assertThat(named.version()).isEqualTo("v1.2.3");
                });
    }

    @Test
    void each_of_the_three_stored_suffixes_names_the_same_version() {
        for (String suffix : List.of(".info", ".mod", ".zip")) {
            assertThat(describe("go/example.com/m/@v/v0.1.0" + suffix))
                    .as("the %s of the trio", suffix)
                    .hasValueSatisfying(named -> assertThat(named.version()).isEqualTo("v0.1.0"));
        }
    }

    @Test
    void a_version_carrying_dots_and_a_build_tag_survives_the_suffix_strip() {
        // Stripping a KNOWN suffix from the end is what makes this deterministic: a Go version carries dots and may
        // carry +incompatible, and none of that collides with .info/.mod/.zip.
        assertThat(describe("go/example.com/m/@v/v2.0.0+incompatible.mod"))
                .hasValueSatisfying(named -> assertThat(named.version()).isEqualTo("v2.0.0+incompatible"));
    }

    @Test
    void the_client_escaping_is_preserved_verbatim() {
        // A Go client escapes upper case as !lower, and blobKeys composes the coordinate verbatim - so the reverse
        // must not normalise it, or the row it rebuilds is keyed differently from the one the publish wrote.
        assertThat(describe("go/github.com/!acme/!widget/@v/v1.0.0.zip"))
                .hasValueSatisfying(named ->
                        assertThat(named.coordinate()).isEqualTo("github.com/!acme/!widget"));
    }

    @Test
    void a_key_that_is_not_a_module_pointer_is_not_claimed() {
        assertThat(describe("go/example.com/m/@v/list")).isEmpty();          // the version list, not a version
        assertThat(describe("go/example.com/m/@latest")).isEmpty();
        assertThat(describe("go/example.com/m")).isEmpty();
        assertThat(describe("npm/left-pad/versions/1.0.0")).isEmpty();
    }

    @Test
    void a_traversal_shaped_key_is_not_claimed() {
        assertThat(describe("go/../../etc/@v/v1.0.0.zip")).isEmpty();
        assertThat(describe("go/example.com/m/@v/../../secret.zip")).isEmpty();
        assertThat(describe("go//@v/v1.0.0.zip")).isEmpty();
    }
}
