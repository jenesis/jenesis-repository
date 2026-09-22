package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;

import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.store.ArtifactDescriptor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The npm key shapes {@code BlobLayoutCoordinateSeamTest}'s round trip cannot reach.
 *
 * <p>That property is the stronger one - it drives {@code describePointer} over keys the format itself wrote for a
 * really published version - but it can only cover the coordinate its fixture publishes, which is a plain
 * unscoped name. The shapes that make this parse non-obvious are exactly the ones a fixture does not happen to
 * publish, so they are stated here: a scoped coordinate carries a slash, and a coordinate may itself end in the
 * word the key is split on.
 *
 * <p>This is not belt-and-braces. A wrong answer here is not a failed read - it writes a {@code published/} row
 * against a release that was never published, and retention ages artifacts by that row.
 */
class NpmPointerDescriptionTest {

    /** The installed npm layout, resolved as the fixtures resolve it - the npm module exports its package to
     *  named modules only, and this suite is not one of them. */
    private static final BlobLayout NPM = new NpmFormatFixture().layout();

    private static Optional<ArtifactDescriptor> describe(String key) {
        return NPM.describePointer(key);
    }

    @Test
    void a_plain_version_pointer_names_its_coordinate() {
        assertThat(describe("npm/left-pad/versions/1.0.0"))
                .hasValueSatisfying(named -> {
                    assertThat(named.ecosystem()).isEqualTo("npm");
                    assertThat(named.coordinate()).isEqualTo("left-pad");
                    assertThat(named.version()).isEqualTo("1.0.0");
                });
    }

    @Test
    void a_scoped_coordinate_keeps_its_slash() {
        // The reason the coordinate is screened part-by-part rather than as one segment: @scope/name is a single
        // npm coordinate that legitimately contains a slash.
        assertThat(describe("npm/@acme/lib/versions/2.3.4"))
                .hasValueSatisfying(named -> {
                    assertThat(named.coordinate()).isEqualTo("@acme/lib");
                    assertThat(named.version()).isEqualTo("2.3.4");
                });
    }

    @Test
    void a_package_whose_name_ends_in_versions_is_read_as_the_package_it_is() {
        // The case that decides first-marker versus last. @acme/versions stores its 1.0.0 at
        // npm/@acme/versions/versions/1.0.0; splitting on the FIRST marker yields the coordinate "@acme" and the
        // version "versions/1.0.0" - a row against a package that does not exist, with a version containing a
        // slash. Splitting on the last yields what was published.
        assertThat(describe("npm/@acme/versions/versions/1.0.0"))
                .hasValueSatisfying(named -> {
                    assertThat(named.coordinate()).isEqualTo("@acme/versions");
                    assertThat(named.version()).isEqualTo("1.0.0");
                });
    }

    @Test
    void a_key_that_is_not_a_version_pointer_is_not_claimed() {
        // Claiming any of these would rebuild a row for something that is not a release. The tarball key is the
        // interesting one: it carries the version inside a filename, and a short name ending in a digit makes that
        // split ambiguous - so this layout does not decode it at all rather than decode it sometimes.
        assertThat(describe("npm/left-pad/tarballs/left-pad-1.0.0.tgz")).isEmpty();
        assertThat(describe("npm/left-pad/dist-tags")).isEmpty();
        assertThat(describe("npm/left-pad")).isEmpty();
        assertThat(describe("oci/library/alpine/tags/3.20")).isEmpty();
        assertThat(describe("blobs/aabbcc")).isEmpty();
    }

    @Test
    void a_traversal_shaped_key_is_not_claimed() {
        // The screen the layout shares with every other blobs-namespace format. A key like this cannot have been
        // written by this format, so decoding it would name a coordinate nothing published - and the row would then
        // be aged, and its "artifacts" evicted, against a name an attacker chose.
        assertThat(describe("npm/../../etc/versions/1.0.0")).isEmpty();
        assertThat(describe("npm/a/versions/../../secret")).isEmpty();
        assertThat(describe("npm//versions/1.0.0")).isEmpty();
        assertThat(describe("npm/a/versions/")).isEmpty();
    }
}
