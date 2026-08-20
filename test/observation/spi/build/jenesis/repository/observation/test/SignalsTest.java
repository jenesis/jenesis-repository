package build.jenesis.repository.observation.test;

import build.jenesis.repository.observation.Signals;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The signal naming grammar - the {@code java.base} form of {@code OBSERVABILITY.md}'s
 * {@code ^jenreg(\.[a-z][a-z0-9]*)+$}: a name reads like the {@code jenreg.<feature>.*} config keys beside it, and a
 * broken one is rejected when the name is built, not when the meter is scraped.
 */
class SignalsTest {

    @Test
    void composes_a_name_from_a_feature_and_segments() {
        assertThat(Signals.name("gc", "reclaimed", "bytes")).isEqualTo("jenreg.gc.reclaimed.bytes");
        assertThat(Signals.name("quota")).isEqualTo("jenreg.quota");
    }

    @Test
    void accepts_a_well_formed_dotted_lowercase_name() {
        assertThat(Signals.valid("jenreg.forwarding.pending")).isTrue();
        assertThat(Signals.valid("jenreg.gate.verdicts")).isTrue();
        assertThat(Signals.valid("jenreg.a1.b2c3")).isTrue();
    }

    @Test
    void rejects_the_documented_anti_patterns() {
        assertThat(Signals.valid(null)).isFalse();
        assertThat(Signals.valid("jenesis")).isFalse();                  // no signal segment
        assertThat(Signals.valid("jenreg_forwarding_pending")).isFalse(); // snake_case
        assertThat(Signals.valid("build.jenesis.forwarding")).isFalse();  // package-style prefix
        assertThat(Signals.valid("jenreg.Forwarding.Pending")).isFalse(); // uppercase
        assertThat(Signals.valid("jenreg.1forwarding")).isFalse();       // digit-led segment
        assertThat(Signals.valid("jenreg.forwarding.")).isFalse();       // trailing dot
        assertThat(Signals.valid("jenreg.for warding")).isFalse();       // space
    }

    @Test
    void require_returns_a_valid_name_and_throws_on_a_broken_one() {
        assertThat(Signals.require("jenreg.cache.requests")).isEqualTo("jenreg.cache.requests");
        assertThatThrownBy(() -> Signals.require("jenreg_cache_requests"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void name_rejects_a_segment_that_breaks_the_grammar() {
        assertThatThrownBy(() -> Signals.name("gc", "Reclaimed"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Signals.name("gc", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Signals.name("gc", (String) null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
