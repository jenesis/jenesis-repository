package build.jenesis.repository.health.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.HealthSource.Health;
import build.jenesis.repository.health.store.HealthSection;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.metadata.SectionMutation;
import build.jenesis.repository.metadata.Signal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code health} section codec in isolation: {@link HealthSection#section} round-trips every field back through
 * {@link HealthSection#stored}; the {@link HealthSection#record} merge is the monotonic-{@code scannedAt} guard - a
 * present-and-fresher (or equal) section stands, a strictly-newer write overwrites; and {@code stored} declines a
 * non-DERIVED section and a DERIVED-but-payload-less one (returning empty rather than fabricating a zero score).
 */
class HealthSectionTest {

    private static final Instant FIRST = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-07-05T00:00:00Z");

    private static final Health H = new Health("github.com/example/lib", 7.2, 8.0, 6.0, 5.0);

    @Test
    void a_section_round_trips_every_field_of_a_health_payload() {
        Optional<HealthSection.Stored> read = HealthSection.stored(Optional.of(HealthSection.section(H, FIRST)));

        assertThat(read).isPresent();
        assertThat(read.get().health().sourceRepository()).isEqualTo("github.com/example/lib");
        assertThat(read.get().health().overall()).isEqualTo(7.2);
        assertThat(read.get().health().maintenance()).isEqualTo(8.0);
        assertThat(read.get().health().review()).isEqualTo(6.0);
        assertThat(read.get().health().provenance()).isEqualTo(5.0);
        assertThat(read.get().scannedAt()).as("the scored instant rides the section's updated field").isEqualTo(FIRST);
    }

    @Test
    void record_keeps_a_present_and_fresher_section_standing() {
        Section current = HealthSection.section(H, LATER);
        // A stale write (an older scored instant) must not roll the health backwards.
        SectionMutation stale = HealthSection.record(new Health("repo", 2.0, 2.0, 2.0, 2.0), FIRST);
        assertThat(HealthSection.stored(Optional.of(stale.apply(Optional.of(current)))).orElseThrow().health().overall())
                .as("a stale refresh leaves the fresher record standing").isEqualTo(7.2);

        // An equal scored instant also leaves the present record standing (not strictly before).
        SectionMutation equal = HealthSection.record(new Health("repo", 3.0, 3.0, 3.0, 3.0), LATER);
        assertThat(HealthSection.stored(Optional.of(equal.apply(Optional.of(current)))).orElseThrow().health().overall())
                .as("an equal-instant refresh does not overwrite").isEqualTo(7.2);
    }

    @Test
    void record_overwrites_on_a_strictly_newer_scanned_at() {
        Section current = HealthSection.section(H, FIRST);
        SectionMutation newer = HealthSection.record(new Health("repo", 3.5, 3.0, 4.0, 3.0), LATER);

        HealthSection.Stored merged = HealthSection.stored(Optional.of(newer.apply(Optional.of(current)))).orElseThrow();
        assertThat(merged.health().overall()).as("a strictly newer refresh replaces the record").isEqualTo(3.5);
        assertThat(merged.scannedAt()).isEqualTo(LATER);
    }

    @Test
    void record_writes_the_section_when_the_coordinate_had_none() {
        Section written = HealthSection.record(H, FIRST).apply(Optional.empty());
        assertThat(HealthSection.stored(Optional.of(written)).orElseThrow().health().overall()).isEqualTo(7.2);
    }

    @Test
    void stored_declines_a_non_derived_or_payload_less_section() {
        assertThat(HealthSection.stored(Optional.empty())).as("an absent section is empty").isEmpty();
        assertThat(HealthSection.stored(Optional.of(Section.empty(HealthSection.TAG, HealthSection.SCHEMA, FIRST))))
                .as("an inspected-but-empty (non-DERIVED) section carries no health").isEmpty();
        assertThat(HealthSection.stored(Optional.of(
                Section.derived(HealthSection.TAG, HealthSection.SCHEMA, FIRST, Signal.NEUTRAL, null))))
                .as("a DERIVED section written without a payload is not a health record").isEmpty();
    }
}
