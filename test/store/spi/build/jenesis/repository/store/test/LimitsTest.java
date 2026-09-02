package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;

import build.jenesis.repository.store.ArchiveInflation;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Limits;
import build.jenesis.repository.store.PublishInterceptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shared reader behind every operator-settable byte ceiling in the product.
 *
 * <p>The three rules it exists to state once are each falsifiable here: the constant is a DEFAULT rather than the
 * law, the read is LIVE rather than latched, and a mistyped value RAISES rather than silently leaving the operator on
 * the old ceiling. The third is the one worth a test - a fallback-on-garbage implementation passes every other
 * assertion in this class while quietly ignoring the configuration it was given.
 */
class LimitsTest {

    private static final String KEY = "jenesis.test.ceiling";

    @AfterEach
    void restoreConfig() {
        Features.reset();
    }

    @Test
    void an_unset_key_answers_the_compiled_default() {
        assertThat(Limits.positive(KEY, 4096)).isEqualTo(4096);
        assertThat(Limits.positive(KEY, 4096L)).isEqualTo(4096L);
        assertThat(Limits.isSet(KEY)).isFalse();

        // Blank is unset: a docker -e that supplies an empty value has not stated a ceiling.
        Features.configure(key -> KEY.equals(key) ? "   " : null);
        assertThat(Limits.positive(KEY, 4096)).isEqualTo(4096);
        assertThat(Limits.isSet(KEY)).isFalse();
    }

    @Test
    void a_configured_key_answers_the_configured_value_and_is_read_live() {
        Features.configure(key -> KEY.equals(key) ? "128" : null);
        assertThat(Limits.positive(KEY, 4096)).isEqualTo(128);
        assertThat(Limits.isSet(KEY)).isTrue();

        // Live, not latched: the second read sees the second configuration. A static field initialised on first read
        // would pass the assertion above and fail this one.
        Features.configure(key -> KEY.equals(key) ? "256" : null);
        assertThat(Limits.positive(KEY, 4096)).isEqualTo(256);
    }

    @Test
    void a_value_that_is_not_a_positive_number_of_bytes_raises_rather_than_falling_back() {
        for (String malformed : List.of("banana", "16 MiB", "1_048_576", "0", "-1", "1.5")) {
            Features.configure(key -> KEY.equals(key) ? malformed : null);
            assertThatThrownBy(() -> Limits.positive(KEY, 4096))
                    .as("a ceiling set to '%s' must not silently answer the default", malformed)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(KEY)
                    .hasMessageContaining("positive number of bytes");
        }
    }

    @Test
    void a_value_past_the_int_range_is_refused_at_an_int_ceiling_and_carried_at_a_long_one() {
        Features.configure(key -> KEY.equals(key) ? "4294967296" : null);

        // Refused rather than truncated: 4 GiB narrowed into an int is 0, which would turn "raise the cap" into
        // "refuse everything" - the exact silent misconfiguration this reader exists to prevent.
        assertThatThrownBy(() -> Limits.positive(KEY, 4096))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(KEY);
        assertThat(Limits.positive(KEY, 4096L)).isEqualTo(4294967296L);
    }

    @Test
    void the_ceilings_that_ride_on_it_move_with_their_keys() {
        assertThat(ArchiveInflation.largestEntry()).isEqualTo(ArchiveInflation.LARGEST_ENTRY);
        assertThat(PublishInterceptor.Content.largestSibling())
                .isEqualTo(PublishInterceptor.Content.LARGEST_SIBLING);

        Features.configure(key -> switch (key) {
            case ArchiveInflation.LARGEST_ENTRY_KEY -> "512";
            case PublishInterceptor.Content.LARGEST_SIBLING_KEY -> "1024";
            default -> null;
        });

        assertThat(ArchiveInflation.largestEntry()).isEqualTo(512);
        assertThat(PublishInterceptor.Content.largestSibling()).isEqualTo(1024);
    }
}
