package build.jenesis.repository.compliance.spi.test;

import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.Severity;

import static org.assertj.core.api.Assertions.assertThat;

/** An advisory's severity in words reads as its band, whichever vendor's word it is; any other word is the caller's. */
class SeverityWordTest {

    @Test
    void every_vendor_word_reads_as_its_band_in_any_case() {
        assertThat(Severity.ofWord("LOW", Severity.NONE)).isEqualTo(Severity.LOW);
        assertThat(Severity.ofWord("Moderate", Severity.NONE)).isEqualTo(Severity.MEDIUM);
        assertThat(Severity.ofWord("middle", Severity.NONE)).as("Socket's word").isEqualTo(Severity.MEDIUM);
        assertThat(Severity.ofWord("high", Severity.NONE)).isEqualTo(Severity.HIGH);
        assertThat(Severity.ofWord("CRITICAL", Severity.NONE)).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void a_word_it_cannot_read_is_the_fallback_the_source_chose() {
        assertThat(Severity.ofWord("severe", Severity.UNKNOWN)).isEqualTo(Severity.UNKNOWN);
        assertThat(Severity.ofWord(null, Severity.NONE)).isEqualTo(Severity.NONE);
    }
}
