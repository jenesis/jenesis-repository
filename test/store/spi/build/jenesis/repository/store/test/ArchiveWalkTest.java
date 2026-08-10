package build.jenesis.repository.store.test;

import build.jenesis.repository.store.ArchiveInflation;
import build.jenesis.repository.store.ArchiveWalk;
import build.jenesis.repository.store.Features;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The product's shared archive-<em>walk</em> bound (D-068): one named, operator-settable ceiling on how far a read may
 * run through an artifact archive looking for the member that declares it, and a walk that reports <em>why</em> it
 * stopped rather than conflating "this archive carries no such member" with "we never reached one" (D-020).
 *
 * <p>The sibling of {@code ArchiveInflationTest}, one dimension over, and it reuses that bound's own
 * {@link ArchiveInflation.Outcome} vocabulary deliberately: an archive read has two ways to be cut short and one way
 * to say so.
 */
class ArchiveWalkTest {

    @AfterEach
    void restoreConfig() {
        Features.reset();
    }

    /** An archive of {@code length} readable bytes - the container is irrelevant here; the budget is over bytes. */
    private static InputStream archive(int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) 'A');
        return new ByteArrayInputStream(bytes);
    }

    /** A walker that draws the whole stream and answers a declaration once it has seen {@code at} bytes. */
    private static ArchiveWalk.Walker<String> findingAt(int at) {
        return screened -> {
            String found = null;
            long seen = 0;
            for (int read; (read = screened.read()) != -1; ) {
                seen++;
                if (seen == at) {
                    found = "declaration@" + read;
                }
            }
            return found;
        };
    }

    @Test
    void a_walk_that_sees_the_archive_out_answers_what_it_found_and_reports_that_it_ended() throws IOException {
        ArchiveWalk.Found<String> found = ArchiveWalk.walk(archive(512), 1024, findingAt(8));

        assertThat(found.exhausted()).isTrue();
        assertThat(found.truncated()).isFalse();
        assertThat(found.consumed()).isEqualTo(512);
        assertThat(found.orNull()).isEqualTo("declaration@65");
    }

    @Test
    void an_archive_that_declares_nothing_is_exhausted_not_truncated() throws IOException {
        // The whole point of the outcome, and the D-020 conflation: an archive that carries no such member and one
        // whose member sat past the bound must never answer the same way.
        ArchiveWalk.Found<String> nothing = ArchiveWalk.walk(archive(512), 1024, _ -> null);

        assertThat(nothing.exhausted()).isTrue();
        assertThat(nothing.orNull()).isNull();
    }

    @Test
    void a_walk_past_the_ceiling_truncates_and_hands_back_nothing_it_passed_on_the_way() throws IOException {
        // The member the walk DID find before the ceiling is dropped: a crafted archive can put a decoy manifest at
        // byte one and the real one past the bound, so handing the early one back would let the archive choose what a
        // screen sees - the walk-side twin of "a truncated member read yields no prefix".
        ArchiveWalk.Found<String> found = ArchiveWalk.walk(archive(4096), 64, findingAt(8));

        assertThat(found.truncated()).as("the ceiling stopped the walk, and the outcome says so").isTrue();
        assertThat(found.orNull())
                .as("nothing at all escapes a bound-stopped walk, not even what it passed before the bound bit")
                .isNull();
        assertThat(found.consumed()).as("the stopping point, not the archive's real size").isEqualTo(64);
    }

    @Test
    void an_archive_ending_exactly_on_the_ceiling_is_exhausted_rather_than_crying_wolf() throws IOException {
        // The look-ahead byte. Without it every archive whose last byte lands on the bound reports as cut off, and a
        // bound that cries wolf is one callers learn to ignore - which is how the outcome stops being read at all.
        assertThat(ArchiveWalk.walk(archive(64), 64, findingAt(8)).exhausted()).isTrue();
        assertThat(ArchiveWalk.walk(archive(64), 64, findingAt(8)).orNull()).isEqualTo("declaration@65");
        assertThat(ArchiveWalk.walk(archive(65), 64, findingAt(8)).truncated()).isTrue();
    }

    @Test
    void bytes_a_container_skips_count_against_the_same_budget_as_bytes_it_reads() throws IOException {
        // A container that jumps over a payload entry has still made the decompressor produce it, so a bound counting
        // only read() would not bound the work at all - it would bound the parsing.
        ArchiveWalk.Found<String> found = ArchiveWalk.walk(archive(4096), 64, screened -> {
            screened.skip(4096);
            return screened.read() == -1 ? null : "unreachable";
        });

        assertThat(found.truncated()).isTrue();
        assertThat(found.consumed()).isEqualTo(64);
    }

    @Test
    void the_two_roles_are_the_two_accessors_and_a_bound_stopped_walk_never_reads_as_an_empty_archive() {
        ArchiveWalk.Found<String> truncated =
                new ArchiveWalk.Found<>(null, ArchiveInflation.Outcome.TRUNCATED, 64);
        ArchiveWalk.Found<String> empty =
                new ArchiveWalk.Found<>(null, ArchiveInflation.Outcome.EXHAUSTED, 512);

        // An optional declaration degrades either way: the artifact publishes and simply declares nothing.
        assertThat(truncated.orNull()).isNull();
        assertThat(empty.orNull()).isNull();

        // The artifact's identity, or a guard's only input, fails closed - and D-020 is exactly this: the refusal must
        // say WHICH of the two happened, or an operator is told a .deb "carries no control stanza" when the walk never
        // reached it.
        assertThatThrownBy(() -> truncated.required("Debian .deb", "control member"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Debian .deb")
                .hasMessageContaining("control member")
                .hasMessageContaining("archive-walk bound")
                .hasMessageContaining(ArchiveWalk.LARGEST_WALK_KEY);
        assertThatThrownBy(() -> empty.required("Debian .deb", "control member"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("carries no control member");

        assertThatCode(() -> new ArchiveWalk.Found<>("found", ArchiveInflation.Outcome.EXHAUSTED, 512)
                .required("a", "b"))
                .as("a walk that saw the archive out answers through the same accessor without failing")
                .doesNotThrowAnyException();
    }

    @Test
    void a_truncated_walk_cannot_be_constructed_carrying_what_it_found() {
        // The equivalence is enforced by the type, exactly as a truncated member read cannot carry bytes: an
        // incomplete walk has no representation that looks complete.
        assertThatThrownBy(() -> new ArchiveWalk.Found<>("found", ArchiveInflation.Outcome.TRUNCATED, 64))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_broken_archive_and_a_bounded_one_are_never_reported_as_each_other() throws IOException {
        // A walker that fails while the bound still had budget is reading a genuinely broken archive, and telling an
        // operator to raise a ceiling that was never reached would send them the wrong way. A walker that fails AFTER
        // the screen cut it off is failing because of the cut - which is the one failure a walk converts to an outcome,
        // and the shape every real container takes when its stream ends mid-entry.
        IOException corrupt = new IOException("not the container it claims to be");
        assertThatThrownBy(() -> ArchiveWalk.walk(archive(64), 1024, _ -> {
            throw corrupt;
        })).isSameAs(corrupt);

        ArchiveWalk.Found<String> cut = ArchiveWalk.walk(archive(4096), 64, screened -> {
            screened.readAllBytes();
            throw new EOFException("unexpected end of archive entry");
        });
        assertThat(cut.truncated()).isTrue();
        assertThat(cut.orNull()).isNull();
    }

    @Test
    void the_default_ceiling_is_sixty_four_mebibytes_and_an_operator_can_move_it() throws IOException {
        assertThat(ArchiveWalk.largestWalk()).isEqualTo(64L * 1024 * 1024);

        Features.configure(key -> ArchiveWalk.LARGEST_WALK_KEY.equals(key) ? "128" : null);
        assertThat(ArchiveWalk.largestWalk()).isEqualTo(128);

        // The configured value is what the no-limit overload applies - the bound is one dial, not a constant with a
        // dial beside it.
        assertThat(ArchiveWalk.walk(archive(256), findingAt(8)).truncated()).isTrue();
        assertThat(ArchiveWalk.walk(archive(64), findingAt(8)).exhausted()).isTrue();
    }

    @Test
    void the_key_is_spelled_the_same_at_its_constant_and_at_its_read_site() {
        // ArchiveWalk reads the key as a literal so ConfigPrincipleTest's read scan sees it; this pins the literal and
        // the exported constant equal so the two cannot drift apart.
        assertThat(ArchiveWalk.LARGEST_WALK_KEY).isEqualTo("jenesis.archive.largest-walk");
    }

    @Test
    void a_misconfigured_ceiling_fails_loudly_rather_than_silently_reverting_to_the_default() {
        for (String bad : List.of("sixty four megabytes", "0", "-1", "67_108_864")) {
            Features.configure(key -> ArchiveWalk.LARGEST_WALK_KEY.equals(key) ? bad : null);
            assertThatThrownBy(ArchiveWalk::largestWalk)
                    .as("an operator who raised the cap and mistyped it must not be left believing they raised it "
                            + "(principle 9), value '%s'", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(ArchiveWalk.LARGEST_WALK_KEY);
        }

        Features.configure(key -> ArchiveWalk.LARGEST_WALK_KEY.equals(key) ? "  " : null);
        assertThat(ArchiveWalk.largestWalk())
                .as("an unset-shaped value is unset, not a typo")
                .isEqualTo(ArchiveWalk.LARGEST_WALK);
    }

    @Test
    void a_body_relative_ceiling_never_falls_below_the_shared_tier_and_never_overflows() {
        // The conda shape: a member that legitimately follows a large payload, so a flat tier would refuse valid large
        // packages. The ratio is the format's own judgement; only the floor is shared.
        assertThat(ArchiveWalk.largestWalk(1024, 100)).isEqualTo(ArchiveWalk.LARGEST_WALK);
        assertThat(ArchiveWalk.largestWalk(8L * 1024 * 1024, 100)).isEqualTo(800L * 1024 * 1024);
        assertThat(ArchiveWalk.largestWalk(0, 100))
                .as("an unknown stored length falls back to the shared tier, never to no bound at all")
                .isEqualTo(ArchiveWalk.LARGEST_WALK);
        assertThat(ArchiveWalk.largestWalk(Long.MAX_VALUE / 2, 100)).isEqualTo(Long.MAX_VALUE);
        assertThatThrownBy(() -> ArchiveWalk.largestWalk(1024, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_non_positive_explicit_limit_is_refused_rather_than_walking_nothing_forever() {
        assertThatThrownBy(() -> ArchiveWalk.walk(archive(4), 0, findingAt(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArchiveWalk.walk(archive(4), -1, findingAt(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
