package build.jenesis.repository.metadata.test;

import module org.junit.jupiter.api;
import build.jenesis.repository.metadata.MetadataKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one canonical metadata key codec: the {@code (ecosystem, coordinate, version)} triple encodes into
 * {@code meta/<eco>/<enc(coord)>/<version>} and the coordinate round-trips back through the URL-decode; a
 * reserved-character coordinate stays a single traversal-free segment; a traversal-laced ecosystem or version is
 * rejected; and a version can never alias the reserved per-coordinate document.
 */
class MetadataKeyTest {

    @Test
    void a_triple_encodes_under_the_canonical_scheme() {
        assertThat(MetadataKey.version("Maven", "org.example:lib", "1.0"))
                .isEqualTo("meta/Maven/org.example%3Alib/1.0");
    }

    @Test
    void the_coordinate_round_trips_through_the_key_segment() {
        String coordinate = "@angular/core";
        String key = MetadataKey.version("npm", coordinate, "17.0.0");
        String encoded = key.split("/")[2];
        assertThat(MetadataKey.decodeCoordinate(encoded)).isEqualTo(coordinate);
    }

    @Test
    void a_reserved_character_coordinate_stays_one_segment() {
        // A coordinate carrying '/' or ':' must not fan out into extra path levels - it is URL-encoded into one
        // segment, so the tree stays exactly eco / coord / version deep.
        String key = MetadataKey.version("go", "github.com/pkg/errors", "v0.9.1");
        assertThat(key.split("/")).hasSize(4);
        assertThat(MetadataKey.decodeCoordinate(key.split("/")[2])).isEqualTo("github.com/pkg/errors");
    }

    @Test
    void the_coordinate_document_uses_the_reserved_segment() {
        assertThat(MetadataKey.coordinate("Maven", "org.example:lib"))
                .isEqualTo("meta/Maven/org.example%3Alib/@coordinate");
    }

    @Test
    void a_version_may_not_alias_the_coordinate_document() {
        assertThatThrownBy(() -> MetadataKey.version("Maven", "org.example:lib", "@coordinate"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_traversal_laced_segment_is_rejected() {
        assertThatThrownBy(() -> MetadataKey.version("..", "org.example:lib", "1.0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MetadataKey.version("Maven", "org.example:lib", "../etc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_bare_slash_version_is_rejected_even_without_dot_dot() {
        // A version carrying a plain path separator - no {@code ..} at all - must still be rejected: it is the
        // ArtifactStore.segment guard, not a substring "../" scan, that keeps the version a single key segment. A
        // "1.0/beta" (the exact shape a PyPI release label can take) would otherwise fan the document out under a
        // "1.0/" pseudo-coordinate, aliasing a neighbouring version's key-space rather than escaping via traversal.
        assertThatThrownBy(() -> MetadataKey.version("PyPI", "flask", "1.0/beta"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
