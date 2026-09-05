package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.LineDocument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link LineDocument}: a magic and version line, named fields, free lines after a blank; a foreign or torn object
 * reads as absent rather than as garbage; the search manifest's existing bytes are exactly what the codec writes.
 */
class LineDocumentTest {

    @Test
    void fields_and_free_lines_round_trip_and_the_bytes_are_the_search_manifests_own() {
        byte[] manifest = LineDocument.of("jenesis-search", 1)
                .field("generation", 7).field("format", 5).field("documents", 12L).field("checksum", "abc").bytes();
        assertThat(new String(manifest, StandardCharsets.UTF_8))
                .isEqualTo("jenesis-search 1\ngeneration 7\nformat 5\ndocuments 12\nchecksum abc\n");
        LineDocument read = LineDocument.parse(manifest, "jenesis-search").orElseThrow();
        assertThat(read.version()).isEqualTo(1);
        assertThat(read.field("generation")).contains("7");
        assertThat(read.field("checksum")).contains("abc");
        assertThat(read.field("missing")).isEmpty();
        assertThat(read.lines()).isEmpty();

        byte[] report = LineDocument.of("jenesis-report", 1).field("status", "DONE").field("failure", null)
                .line("row one").line("row\ntwo").bytes();
        LineDocument rows = LineDocument.parse(report, "jenesis-report").orElseThrow();
        assertThat(rows.field("status")).contains("DONE");
        assertThat(rows.field("failure")).as("an absent value is an empty field, still present").contains("");
        assertThat(rows.lines()).as("a newline inside a line becomes a space, so a line stays a line")
                .containsExactly("row one", "row two");
    }

    @Test
    void a_foreign_torn_or_older_object_reads_as_absent() {
        assertThat(LineDocument.parse("DONE\n2026-09-05T00:00:00Z\n\n3\n".getBytes(StandardCharsets.UTF_8), "jenesis-report"))
                .as("the positional report of old: no magic, no document").isEmpty();
        assertThat(LineDocument.parse(new byte[0], "jenesis-report")).isEmpty();
        assertThat(LineDocument.parse("jenesis-listing/1\nseq=3\n".getBytes(StandardCharsets.UTF_8), "jenesis-report"))
                .as("another document's magic").isEmpty();
        assertThat(LineDocument.parse("jenesis-report x\n".getBytes(StandardCharsets.UTF_8), "jenesis-report"))
                .as("a version that does not parse").isEmpty();
        assertThat(LineDocument.parse("jenesis-report 2\nstatus DONE\n".getBytes(StandardCharsets.UTF_8), "jenesis-report")
                .orElseThrow().version()).as("a newer version is read, for the caller to refuse").isEqualTo(2);
    }

    @Test
    void names_carry_no_space_or_newline() {
        assertThatThrownBy(() -> LineDocument.of("two words", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LineDocument.of("ok", 1).field("bad name", "v")).isInstanceOf(IllegalArgumentException.class);
    }
}
