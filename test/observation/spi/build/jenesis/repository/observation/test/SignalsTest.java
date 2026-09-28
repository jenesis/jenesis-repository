package build.jenesis.repository.observation.test;

import module org.junit.jupiter.api;

import build.jenesis.repository.observation.Signals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The signal naming grammar - the {@code java.base} form of the observability naming grammar's
 * {@code ^jenrepo(\.[a-z][a-z0-9]*)+$}: a name reads like the {@code jenrepo.<feature>.*} config keys beside it, and a
 * broken one is rejected when the name is built, not when the meter is scraped.
 */
class SignalsTest {

    @Test
    void composes_a_name_from_a_feature_and_segments() {
        assertThat(Signals.name("gc", "reclaimed", "bytes")).isEqualTo("jenrepo.gc.reclaimed.bytes");
        assertThat(Signals.name("quota")).isEqualTo("jenrepo.quota");
    }

    @Test
    void accepts_a_well_formed_dotted_lowercase_name() {
        assertThat(Signals.valid("jenrepo.forwarding.pending")).isTrue();
        assertThat(Signals.valid("jenrepo.gate.verdicts")).isTrue();
        assertThat(Signals.valid("jenrepo.a1.b2c3")).isTrue();
    }

    @Test
    void rejects_the_documented_anti_patterns() {
        assertThat(Signals.valid(null)).isFalse();
        assertThat(Signals.valid("jenesis")).isFalse();                  // no signal segment
        assertThat(Signals.valid("jenrepo_forwarding_pending")).isFalse(); // snake_case
        assertThat(Signals.valid("build.jenesis.forwarding")).isFalse();  // package-style prefix
        assertThat(Signals.valid("jenrepo.Forwarding.Pending")).isFalse(); // uppercase
        assertThat(Signals.valid("jenrepo.1forwarding")).isFalse();       // digit-led segment
        assertThat(Signals.valid("jenrepo.forwarding.")).isFalse();       // trailing dot
        assertThat(Signals.valid("jenrepo.for warding")).isFalse();       // space
    }

    @Test
    void require_returns_a_valid_name_and_throws_on_a_broken_one() {
        assertThat(Signals.require("jenrepo.cache.requests")).isEqualTo("jenrepo.cache.requests");
        assertThatThrownBy(() -> Signals.require("jenrepo_cache_requests"))
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
