package build.jenesis.repository.store.test;

import build.jenesis.repository.store.ArchiveInflation;
import build.jenesis.repository.store.Features;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The product's shared archive-inflation bound (D-054): one named, operator-settable ceiling on how many decompressed
 * bytes of a single archive member a format may materialise, and a read that reports <em>why</em> it stopped rather
 * than conflating "this archive declares nothing" with "the bound stopped us looking".
 */
class ArchiveInflationTest {

    @AfterEach
    void restoreConfig() {
        Features.reset();
    }

    private static ArchiveInflation.Entry read(byte[] member, int limit) throws IOException {
        return ArchiveInflation.entry(new ByteArrayInputStream(member), limit);
    }

    @Test
    void a_member_within_the_ceiling_is_read_whole_and_reports_that_it_ended() throws IOException {
        ArchiveInflation.Entry entry = read("Manifest-Version: 1.0".getBytes(StandardCharsets.UTF_8), 1024);

        assertThat(entry.exhausted()).isTrue();
        assertThat(entry.truncated()).isFalse();
        assertThat(entry.inflated()).isEqualTo(21);
        assertThat(new String(entry.orNull(), StandardCharsets.UTF_8)).isEqualTo("Manifest-Version: 1.0");
    }

    @Test
    void an_empty_member_is_exhausted_not_truncated() throws IOException {
        // The whole point of the outcome: an archive that carries an empty member and one whose member was cut off by
        // the ceiling must never answer the same way, or a format cannot tell "declares nothing" from "unread".
        ArchiveInflation.Entry empty = read(new byte[0], 1024);

        assertThat(empty.exhausted()).isTrue();
        assertThat(empty.orNull()).isEmpty();
    }

    @Test
    void a_member_past_the_ceiling_truncates_and_hands_back_no_prefix() throws IOException {
        byte[] bomb = new byte[4096];
        Arrays.fill(bomb, (byte) 'A');

        ArchiveInflation.Entry entry = read(bomb, 64);

        assertThat(entry.truncated()).as("the ceiling stopped the read, and the outcome says so").isTrue();
        assertThat(entry.orNull())
                .as("no prefix escapes: half a manifest parses as a shorter manifest, so a crafted member could "
                        + "otherwise choose what a screen sees")
                .isNull();
        assertThat(entry.inflated()).as("the stopping point, not the member's real size - which the bound refuses "
                + "to find out").isEqualTo(64);
    }

    @Test
    void a_member_exactly_at_the_ceiling_is_read_whole() throws IOException {
        byte[] exact = new byte[64];
        Arrays.fill(exact, (byte) 'A');

        assertThat(read(exact, 64).exhausted()).isTrue();
        assertThat(read(new byte[65], 64).truncated()).isTrue();
    }

    @Test
    void the_two_roles_are_the_two_accessors_and_a_truncated_read_never_reads_as_absent() throws IOException {
        ArchiveInflation.Entry truncated = read(new byte[4096], 64);

        // An optional declaration degrades: the artifact publishes and simply declares nothing.
        assertThat(truncated.orNull()).isNull();

        // The artifact's identity, or a guard's only input, fails closed - and the refusal says which of the two
        // happened, so an operator is never told an archive "carries no manifest" when it was never read.
        assertThatThrownBy(() -> truncated.required("Maven index record", "compressed field 'd'"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Maven index record")
                .hasMessageContaining("compressed field 'd'")
                .hasMessageContaining("archive-inflation bound")
                .hasMessageContaining(ArchiveInflation.LARGEST_ENTRY_KEY);

        assertThatCode(() -> read("declaration".getBytes(StandardCharsets.UTF_8), 1024).required("a", "b"))
                .as("a member read whole answers through the same accessor without failing")
                .doesNotThrowAnyException();
    }

    @Test
    void a_truncated_entry_cannot_be_constructed_carrying_bytes() {
        // The equivalence is enforced by the type, exactly as a bounded traversal result cannot be exhausted and carry
        // a continuation cursor: an incomplete read has no representation that looks complete.
        assertThatThrownBy(() -> new ArchiveInflation.Entry(new byte[4], ArchiveInflation.Outcome.TRUNCATED, 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArchiveInflation.Entry(null, ArchiveInflation.Outcome.EXHAUSTED, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_default_ceiling_is_one_mebibyte_and_an_operator_can_move_it() throws IOException {
        assertThat(ArchiveInflation.largestEntry()).isEqualTo(1 << 20);

        Features.configure(key -> ArchiveInflation.LARGEST_ENTRY_KEY.equals(key) ? "128" : null);
        assertThat(ArchiveInflation.largestEntry()).isEqualTo(128);

        // The configured value is what the no-limit overload applies - the bound is one dial, not a constant with a
        // dial beside it.
        assertThat(ArchiveInflation.entry(new ByteArrayInputStream(new byte[256])).truncated()).isTrue();
        assertThat(ArchiveInflation.entry(new ByteArrayInputStream(new byte[64])).exhausted()).isTrue();
    }

    @Test
    void the_key_is_spelled_the_same_at_its_constant_and_at_its_read_site() {
        // ArchiveInflation reads the key as a literal so ConfigPrincipleTest's read scan sees it; this pins the
        // literal and the exported constant equal so the two cannot drift apart.
        assertThat(ArchiveInflation.LARGEST_ENTRY_KEY).isEqualTo("jenesis.archive.largest-entry");
    }

    @Test
    void a_misconfigured_ceiling_fails_loudly_rather_than_silently_reverting_to_the_default() {
        for (String bad : List.of("one megabyte", "0", "-1", "1_048_576")) {
            Features.configure(key -> ArchiveInflation.LARGEST_ENTRY_KEY.equals(key) ? bad : null);
            assertThatThrownBy(ArchiveInflation::largestEntry)
                    .as("an operator who raised the cap and mistyped it must not be left believing they raised it "
                            + "(principle 9), value '%s'", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(ArchiveInflation.LARGEST_ENTRY_KEY);
        }

        Features.configure(key -> ArchiveInflation.LARGEST_ENTRY_KEY.equals(key) ? "  " : null);
        assertThat(ArchiveInflation.largestEntry())
                .as("an unset-shaped value is unset, not a typo")
                .isEqualTo(ArchiveInflation.LARGEST_ENTRY);
    }

    @Test
    void a_non_positive_explicit_limit_is_refused_rather_than_reading_nothing_forever() {
        assertThatThrownBy(() -> read(new byte[4], 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> read(new byte[4], -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
