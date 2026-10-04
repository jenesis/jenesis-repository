package build.jenesis.repository.format.test;

import module org.junit.jupiter.api;
import build.jenesis.repository.format.Listings;

import static org.assertj.core.api.Assertions.assertThat;

/** A listing page's text is escaped one way and read back the other: the two are inverses, in one pass. */
class ListingsHtmlTest {

    @Test
    void what_html_escapes_unhtml_reads_back() {
        String name = "a<b>&\"c\".whl";
        assertThat(Listings.unhtml(Listings.html(name))).isEqualTo(name);
    }

    @Test
    void an_escaped_ampersand_stays_the_text_it_encodes() {
        // Decoding &amp; first and then &lt; would turn the literal text "&lt;" into "<".
        assertThat(Listings.unhtml("&amp;lt;")).isEqualTo("&lt;");
    }

    @Test
    void the_references_a_generator_emits_besides_are_read_and_an_unknown_one_stays() {
        assertThat(Listings.unhtml("it&#39;s &apos;quoted&apos; &#x41;")).isEqualTo("it's 'quoted' A");
        assertThat(Listings.unhtml("&nbsp; &#xZZ; & alone")).isEqualTo("&nbsp; &#xZZ; & alone");
    }
}
