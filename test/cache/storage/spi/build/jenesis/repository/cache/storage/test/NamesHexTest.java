package build.jenesis.repository.cache.storage.test;

import module org.junit.jupiter.api;
import build.jenesis.repository.cache.storage.Names;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boundary coverage for {@link build.jenesis.repository.cache.storage.Names#isHex} (Names.java:42-53), the one shared predicate
 * every storage backend applies before a {@code step}/{@code inputs} pair becomes an object key (see e.g.
 * {@code GcsStorage.toEntry}, {@code S3Storage.toEntry}, {@code AzureBlobStorage.toEntry}). Two properties are pinned
 * so a "simplification" cannot quietly widen what lands in the key-space:
 *
 * <ul>
 *   <li><b>ASCII-only.</b> {@code isHex} scans explicit {@code 0-9/a-f/A-F} ranges precisely because the tempting
 *       {@code Character.digit(c, 16) >= 0} shortcut would also accept Unicode digits and fullwidth letters. A
 *       fullwidth zero {@code '０'} (U+FF10) - which {@code Character.digit(.,16)} maps to {@code 0}, asserted here so
 *       the trap is demonstrably real - and a fullwidth {@code 'Ａ'} (U+FF21) must both be rejected.</li>
 *   <li><b>1..128 length cap.</b> Empty is rejected (a zero-length segment), a 128-char run is accepted, and a
 *       129-char run is rejected, so an entry segment can never grow unbounded.</li>
 * </ul>
 *
 * Each assertion fails if the guard regresses to {@code Character.digit} (the fullwidth cases would start passing) or
 * drops the emptiness / {@code > 128} bounds (the length cases would flip).
 */
class NamesHexTest {

    @Test
    void plain_ascii_hex_is_accepted_in_both_cases() {
        assertThat(Names.isHex("0")).isTrue();
        assertThat(Names.isHex("00ff")).isTrue();
        assertThat(Names.isHex("AbCdEf0123456789")).as("upper- and lower-case ASCII hex both pass").isTrue();
    }

    @Test
    void non_hex_ascii_is_rejected() {
        assertThat(Names.isHex("g")).as("'g' is past 'f'").isFalse();
        assertThat(Names.isHex("0x1f")).as("an 'x' is not a hex digit").isFalse();
        assertThat(Names.isHex("aa/bb")).as("a path separator is not hex").isFalse();
        assertThat(Names.isHex("aa bb")).as("whitespace is not hex").isFalse();
    }

    @Test
    void null_and_empty_are_rejected() {
        assertThat(Names.isHex(null)).isFalse();
        assertThat(Names.isHex("")).as("a zero-length segment is not a valid entry key part").isFalse();
    }

    @Test
    void the_predicate_is_ascii_only_not_unicode_digit_aware() {
        // Character.digit(., 16) - the shortcut Names deliberately avoids - DOES decode the fullwidth zero to 0, so the
        // ASCII-range scan is load-bearing: it is the only thing standing between a fullwidth code point and an object
        // key. If isHex regressed to Character.digit, these would all start returning true.
        assertThat(Character.digit('０', 16))
                .as("Character.digit accepts the fullwidth zero - proving the ASCII scan is what rejects it")
                .isEqualTo(0);
        assertThat(Names.isHex("０")).as("a fullwidth zero '０' (U+FF10) must be rejected").isFalse();
        assertThat(Names.isHex("Ａ")).as("a fullwidth 'Ａ' (U+FF21) must be rejected").isFalse();
        assertThat(Names.isHex("0０")).as("a mix of ASCII hex and a fullwidth digit is still rejected").isFalse();
    }

    @Test
    void the_length_is_capped_at_1_to_128() {
        assertThat(Names.isHex("a")).as("the minimum length is 1").isTrue();
        assertThat(Names.isHex("a".repeat(128))).as("exactly 128 hex characters is the upper bound and is accepted")
                .isTrue();
        assertThat(Names.isHex("a".repeat(129))).as("129 characters exceeds the cap and is rejected").isFalse();
    }
}
