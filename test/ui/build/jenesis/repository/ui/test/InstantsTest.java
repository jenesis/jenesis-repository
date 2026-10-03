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
    void an_instant_reads_to_a_machine_as_iso_and_anything_else_as_nothing() {
        assertThat(Instants.DISPLAY.iso(Instant.parse("2026-09-27T01:01:22.271197817Z")))
                .as("the datetime a <time> element carries, which the script converts").isEqualTo("2026-09-27T01:01:22Z");
        assertThat(Instants.DISPLAY.iso("2026-09-27T01:01:22Z")).isEqualTo("2026-09-27T01:01:22Z");
        assertThat(Instants.DISPLAY.iso("never")).as("no datetime, so the text stands as it is").isNull();
        assertThat(Instants.DISPLAY.iso(null)).isNull();
    }

    @Test
    void other_text_and_nothing_pass_as_they_are() {
        assertThat(Instants.DISPLAY.display("never")).isEqualTo("never");
        assertThat(Instants.DISPLAY.display(null)).isEmpty();
    }
}
