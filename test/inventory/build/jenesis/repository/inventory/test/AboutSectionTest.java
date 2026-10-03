package build.jenesis.repository.inventory.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.inventory.AboutSection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a version's manifests said about it, merged as each of its files is recorded: a description replaces the one
 * held, and keywords and authors are unioned - each once, however many of the version's files name them.
 */
class AboutSectionTest {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

    @Test
    void every_file_of_a_version_naming_an_author_records_the_author_once() {
        AboutSection.About pom = new AboutSection.About("A library", List.of("tooling"), List.of("Ada Lovelace"));
        AboutSection.About module = new AboutSection.About(null, List.of("tooling", "jvm"),
                List.of("Ada Lovelace", "Charles Babbage"));

        AboutSection.About merged = AboutSection.about(Optional.of(AboutSection.record(module, NOW)
                .apply(Optional.of(AboutSection.section(pom, NOW))))).orElseThrow();

        assertThat(merged.authors()).containsExactly("Ada Lovelace", "Charles Babbage");
        assertThat(merged.keywords()).containsExactly("tooling", "jvm");
        assertThat(merged.description()).as("a file that says nothing of it keeps the held description")
                .isEqualTo("A library");
    }
}
