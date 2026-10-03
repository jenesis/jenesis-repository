package build.jenesis.repository.ui.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.ui.DurationWords;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A duration as every screen says it: a setting in the largest unit that states it exactly, and a measured run
 * rounded to what a reader takes in.
 */
class DurationWordsTest {

    private static final DurationWords WORDS = DurationWords.DISPLAY;

    @Test
    void a_setting_reads_in_the_largest_exact_unit() {
        assertThat(WORDS.words("P30D")).isEqualTo("30 days");
        assertThat(WORDS.words(Duration.ofHours(6))).isEqualTo("6 hours");
        assertThat(WORDS.words(Duration.ofSeconds(90))).isEqualTo("90 seconds");
        assertThat(WORDS.words("none")).isEqualTo("never");
        assertThat(WORDS.words(null)).isEqualTo("-");
        assertThat(WORDS.words(" ")).isEqualTo("-");
    }

    @Test
    void a_measured_run_is_rounded_to_what_a_reader_takes_in() {
        assertThat(WORDS.measured(Duration.ofMillis(420))).isEqualTo("420 milliseconds");
        assertThat(WORDS.measured(Duration.parse("PT3.935164552S"))).isEqualTo("4 seconds");
        assertThat(WORDS.measured(Duration.ofSeconds(1))).isEqualTo("1 second");
        assertThat(WORDS.measured(Duration.ofSeconds(724))).isEqualTo("12 minutes 4 seconds");
        assertThat(WORDS.measured(Duration.ofMinutes(5))).isEqualTo("5 minutes");
        assertThat(WORDS.measured(Duration.ofMinutes(185))).isEqualTo("3 hours 5 minutes");
        assertThat(WORDS.measured(null)).isEqualTo("-");
    }
}
