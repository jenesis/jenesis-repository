package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.Checksums;

import static org.assertj.core.api.Assertions.assertThat;

/** A digest as hex and back: the hex every format serves, and a declared one read only when it is one. */
class ChecksumsTest {

    @Test
    void a_text_hashes_as_its_utf8_bytes() {
        assertThat(Checksums.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
                .isEqualTo(Checksums.sha256("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void a_declared_digest_is_read_only_at_its_length_and_only_as_hex() {
        String hex = Checksums.sha256("abc");
        assertThat(Checksums.parse(hex, 32)).isEqualTo(Checksums.digest("SHA-256",
                "abc".getBytes(StandardCharsets.UTF_8)));
        assertThat(Checksums.parse(hex, 20)).as("another length").isNull();
        assertThat(Checksums.parse("zz".repeat(32), 32)).as("not hex").isNull();
        assertThat(Checksums.parse(null, 32)).as("none declared").isNull();
    }
}
