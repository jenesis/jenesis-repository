package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;

import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.store.ArtifactDescriptor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Debian key shapes the fixtured round trip cannot reach.
 *
 * <p>Debian is the one format here whose coordinate and version live in a <strong>filename</strong> rather than in
 * path segments, and the only thing that makes decoding it safe is a rule of the ecosystem: a {@code .deb} is
 * named {@code <name>_<version>_<arch>.deb} and Debian policy forbids an underscore in a package name or a
 * version. Every other filename-shaped key in this product is left undecoded for want of exactly that guarantee -
 * npm's {@code <shortName>-<version>.tgz} and Cargo's {@code <crate>-<version>} both split on a character their
 * names may contain.
 *
 * <p>So these assert the guarantee rather than the parse: a version carrying every character Debian does allow -
 * epochs, dots, tildes, hyphens - still lands on the right side of the split, and a name carrying a hyphen does
 * too. If the underscore rule ever stopped holding, this is what would say so.
 */
class DebianPointerDescriptionTest {

    private static final BlobLayout DEBIAN = new DebianFormatFixture().layout();

    private static Optional<ArtifactDescriptor> describe(String key) {
        return DEBIAN.describePointer(key);
    }

    @Test
    void a_pool_deb_names_its_package_and_version() {
        assertThat(describe("debian/stable/pool/main/h/hello/hello_2.10-3_amd64.deb"))
                .hasValueSatisfying(named -> {
                    assertThat(named.coordinate()).isEqualTo("hello");
                    assertThat(named.version()).isEqualTo("2.10-3");
                });
    }

    @Test
    void a_hyphenated_name_and_a_hyphenated_version_both_survive_the_split() {
        // The case that would break a hyphen split, which is why the underscore one is used: both sides carry
        // hyphens and neither may carry an underscore.
        assertThat(describe("debian/stable/pool/main/l/libfoo-bar/libfoo-bar_1.2.3-4_arm64.deb"))
                .hasValueSatisfying(named -> {
                    assertThat(named.coordinate()).isEqualTo("libfoo-bar");
                    assertThat(named.version()).isEqualTo("1.2.3-4");
                });
    }

    @Test
    void an_epoch_and_a_tilde_in_the_version_are_kept_verbatim() {
        // A Debian version legitimately carries an epoch (1:) and a tilde (a pre-release ordering marker). Both
        // must land in the version verbatim, because the row is keyed by exactly this string.
        assertThat(describe("debian/stable/pool/main/n/nano/nano_1:7.2~rc1-1_amd64.deb"))
                .hasValueSatisfying(named -> assertThat(named.version()).isEqualTo("1:7.2~rc1-1"));
    }

    @Test
    void a_key_that_is_not_a_deb_is_not_claimed() {
        assertThat(describe("debian/stable/pool/main/h/hello/hello_2.10-3_amd64.udeb")).isEmpty();
        assertThat(describe("debian/stable/dists/stable/Release")).isEmpty();
        assertThat(describe("debian/stable/by/hello/2.10-3/x")).isEmpty();
        assertThat(describe("npm/left-pad/versions/1.0.0")).isEmpty();
    }

    @Test
    void a_filename_without_the_two_underscore_fields_is_not_claimed() {
        assertThat(describe("debian/stable/pool/main/h/hello/hello.deb")).isEmpty();
    }

    @Test
    void a_traversal_shaped_name_or_version_is_not_claimed() {
        assertThat(describe("debian/stable/pool/main/h/x/.._..__amd64.deb")).isEmpty();
        assertThat(describe("debian/stable/pool/main/h/x/hello_.._amd64.deb")).isEmpty();
    }
}
