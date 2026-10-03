package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.Durations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A duration dial reads alike wherever it is set: unset is its default, an off word is zero, anything else is the
 *  one grammar or a refusal naming the dial. */
class DurationsTest {

    @Test
    void a_dial_is_its_default_unset_zero_switched_off_and_otherwise_the_grammar() {
        Duration fallback = Duration.ofMinutes(5);
        assertThat(Durations.dial(null, "jenrepo.probe", fallback)).isEqualTo(fallback);
        assertThat(Durations.dial("  ", "jenrepo.probe", fallback)).isEqualTo(fallback);
        for (String off : List.of("0", "off", "OFF", " false ")) {
            assertThat(Durations.dial(off, "jenrepo.probe", fallback)).as(off).isZero();
        }
        assertThat(Durations.dial("6h", "jenrepo.probe", fallback)).isEqualTo(Duration.ofHours(6));
        assertThat(Durations.dial("PT30S", "jenrepo.probe", fallback)).isEqualTo(Duration.ofSeconds(30));
        assertThat(Durations.dial("2d", "jenrepo.probe", fallback)).isEqualTo(Duration.ofDays(2));
    }

    @Test
    void a_value_that_is_no_duration_is_refused_naming_the_dial() {
        assertThatThrownBy(() -> Durations.dial("soon", "jenrepo.probe", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("jenrepo.probe=soon is not a duration");
        assertThatThrownBy(() -> Durations.dial("30", "jenrepo.probe", Duration.ZERO))
                .as("a bare number's unit is not guessed").isInstanceOf(IllegalArgumentException.class);
    }
}
