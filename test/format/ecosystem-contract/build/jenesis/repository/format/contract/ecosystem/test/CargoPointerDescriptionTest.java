package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;

import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.store.ArtifactDescriptor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Cargo key shapes the fixtured round trip cannot reach.
 *
 * <p>Cargo is the clearest case for the rule that only the unambiguous shape is decoded. Its index key puts every
 * part in a fixed position, {@code cargo/<repo>/index.d/<crate>/<version>}; the {@code .crate} archive beside it
 * spells the pair as {@code <crate>-<version>}, and a crate name may itself contain a hyphen - so that key is
 * deliberately not claimed. The fixture publishes one crate whose name carries no hyphen, which is exactly the
 * case that would make a naive filename split look correct.
 */
class CargoPointerDescriptionTest {

    private static final BlobLayout CARGO = new CargoFormatFixture().layout();

    private static Optional<ArtifactDescriptor> describe(String key) {
        return CARGO.describePointer(key);
    }

    @Test
    void an_index_key_names_its_crate_and_version() {
        assertThat(describe("cargo/crates-io/index.d/serde/1.0.0"))
                .hasValueSatisfying(named -> {
                    assertThat(named.coordinate()).isEqualTo("serde");
                    assertThat(named.version()).isEqualTo("1.0.0");
                });
    }

    @Test
    void a_crate_whose_name_carries_a_hyphen_is_read_from_its_segments_not_a_filename() {
        // The case the archive key cannot answer: <crate>-<version> is "async-std-1.12.0", and splitting that on
        // the last hyphen happens to work while splitting on the first does not - so it is not split at all.
        assertThat(describe("cargo/crates-io/index.d/async-std/1.12.0"))
                .hasValueSatisfying(named -> {
                    assertThat(named.coordinate()).isEqualTo("async-std");
                    assertThat(named.version()).isEqualTo("1.12.0");
                });
    }

    @Test
    void the_crate_archive_is_not_claimed() {
        // Deliberate: the pair is inside a filename there, and a hyphenated crate name makes the split ambiguous.
        // Every published version has an index entry, so nothing is lost.
        assertThat(describe("cargo/crates-io/crates/async-std/async-std-1.12.0.crate")).isEmpty();
    }

    @Test
    void a_key_that_is_not_a_version_entry_is_not_claimed() {
        assertThat(describe("cargo/crates-io/index.d/serde")).isEmpty();          // the crate's folder
        assertThat(describe("cargo/crates-io/index.d/serde/1.0.0/extra")).isEmpty();
        assertThat(describe("cargo/crates-io/config.json")).isEmpty();
        assertThat(describe("npm/left-pad/versions/1.0.0")).isEmpty();
    }

    @Test
    void a_traversal_shaped_key_is_not_claimed() {
        assertThat(describe("cargo/crates-io/index.d/../../secret/1.0.0")).isEmpty();
        assertThat(describe("cargo/crates-io/index.d/serde/..")).isEmpty();
        assertThat(describe("cargo/crates-io/index.d//1.0.0")).isEmpty();
    }
}
