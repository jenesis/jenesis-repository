package build.jenesis.repository.ui.test;

import module java.base;
import module org.junit.jupiter.api;

import build.jenesis.repository.ui.Panel;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one escaper every panel interpolates through, asserted once.
 *
 * <p>{@code Panel} clause 12 says a fragment is dropped into the shell UNESCAPED, so escaping is the panel's
 * obligation - and it was met by four byte-identical private copies in this module and a fifth in downstream. Four
 * are now one, which only helps if the one is right and stays right, so its behaviour is pinned here rather than
 * inferred from four call sites.
 */
class PanelEscapeTest {

    @Test
    void every_entity_that_can_close_a_tag_or_an_attribute_is_neutralised() {
        assertThat(Panel.escape("<script>alert('x')</script>"))
                .as("angle brackets end an element and the apostrophe ends a single-quoted attribute")
                .isEqualTo("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;");
        assertThat(Panel.escape("a \"quoted\" value"))
                .as("the double quote ends a double-quoted attribute")
                .isEqualTo("a &quot;quoted&quot; value");
    }

    @Test
    void the_ampersand_is_escaped_first_so_an_entity_is_not_escaped_twice() {
        // If & were replaced after < , "&lt;" would become "&amp;lt;" and the page would show the escape rather
        // than the character. Ordering is the half of an escaper that a rewrite gets wrong silently.
        assertThat(Panel.escape("&lt;")).isEqualTo("&amp;lt;");
        assertThat(Panel.escape("a & b < c")).isEqualTo("a &amp; b &lt; c");
    }

    @Test
    void an_absent_value_renders_as_nothing_rather_than_the_word_null() {
        // The downstream copy always had this guard and the four here did not, so a panel interpolating an absent
        // value would have written "null" into the page. Folding them onto one took the safer of the two.
        assertThat(Panel.escape(null)).isEmpty();
        assertThat(Panel.escape("")).isEmpty();
    }

    @Test
    void text_that_needs_no_escaping_is_returned_unchanged() {
        assertThat(Panel.escape("org.example:lib:1.0")).isEqualTo("org.example:lib:1.0");
    }
}
