package build.jenesis.repository.ui.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.ui.Instants;

import static org.assertj.core.api.Assertions.assertThat;

class InstantsTest {

    @Test
    void an_instant_shows_to_the_second_in_utc() {
        assertThat(Instants.DISPLAY.display(Instant.parse("2026-09-27T01:01:22.271197817Z")))
                .isEqualTo("2026-09-27 01:01:22 UTC");
    }

    @Test
    void iso_text_shows_the_same_way() {
        assertThat(Instants.DISPLAY.display("2026-09-27T01:01:22.271197817Z")).isEqualTo("2026-09-27 01:01:22 UTC");
    }

    @Test
    void other_text_and_nothing_pass_as_they_are() {
        assertThat(Instants.DISPLAY.display("never")).isEqualTo("never");
        assertThat(Instants.DISPLAY.display(null)).isEmpty();
    }
}
