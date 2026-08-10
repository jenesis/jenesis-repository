package build.jenesis.repository.ui.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.ui.ConsoleController;
import build.jenesis.repository.ui.ConsoleController.RenderedPanel;
import build.jenesis.repository.ui.Panel;
import org.springframework.ui.ExtendedModelMap;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the console does with a panel that throws. One panel must never decide whether the console exists: the render
 * loop contains a panel's failure to that panel's own card, so every other panel renders, the failed panel keeps its
 * navigation entry and its anchor, and the card says it failed rather than looking like a panel with nothing to show.
 *
 * <p>The panels here read no artifact data, so a null store is fine (the pattern the live-API panel tests already
 * use).
 */
class ConsoleControllerTest {

    /** The message a failing panel's exception carries - uncontrolled text the console must not render. */
    private static final String SECRET = "cannot read /srv/store/acme/secret-key.pem";

    private record HealthyPanel(String id, String title) implements Panel {

        @Override
        public String render(ArtifactStore store) {
            return "<p>" + id + " rendered</p>";
        }
    }

    /** A panel that declares itself but cannot render - the common case (a store read that failed). */
    private record BrokenPanel(String id, String title) implements Panel {

        @Override
        public String render(ArtifactStore store) throws IOException {
            throw new IOException(SECRET);
        }
    }

    /** A panel that cannot even declare itself: nothing about it is usable, and it still may not 500 the console. */
    private static final class UndeclarablePanel implements Panel {

        @Override
        public String id() {
            throw new IllegalStateException("no id");
        }

        @Override
        public String title() {
            return "  ";
        }

        @Override
        public String render(ArtifactStore store) {
            throw new IllegalStateException("no body");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<RenderedPanel> render(List<Panel> panels) {
        ExtendedModelMap model = new ExtendedModelMap();
        // Before containment this loop propagated, so a single throwing panel made GET /console a 500 - and this call
        // threw right here.
        assertThat(new ConsoleController(panels, null).console(model)).isEqualTo("console");
        return (List<RenderedPanel>) model.getAttribute("panels");
    }

    @Test
    void one_failing_panel_neither_ends_the_page_nor_hides_the_panels_around_it() {
        List<RenderedPanel> rendered = render(List.of(
                new HealthyPanel("first", "First"),
                new BrokenPanel("broken", "Broken"),
                new HealthyPanel("last", "Last")));

        assertThat(rendered).extracting(RenderedPanel::getId)
                .as("every panel keeps its place; a failed panel that vanished would read as one with nothing to say")
                .containsExactly("first", "broken", "last");
        assertThat(rendered).extracting(RenderedPanel::getBody)
                .as("both healthy panels rendered their real bodies")
                .containsExactly("<p>first rendered</p>", "", "<p>last rendered</p>");
        assertThat(rendered).extracting(RenderedPanel::isFailed).containsExactly(false, true, false);
    }

    @Test
    void the_failed_panel_keeps_its_navigation_entry_and_says_why_its_card_is_empty() {
        RenderedPanel failed = render(List.of(new BrokenPanel("broken", "Broken"))).getFirst();

        assertThat(failed.getId()).as("the anchor a bookmark points at survives the failure").isEqualTo("broken");
        assertThat(failed.getTitle()).as("and so does the navigation title").isEqualTo("Broken");
        assertThat(failed.getBody()).as("no half-rendered body is served").isEmpty();
        assertThat(failed.getFailure())
                .as("the notice names the panel that failed and the kind of failure")
                .contains(BrokenPanel.class.getName()).contains("IOException")
                .contains("Every other panel is unaffected");
        assertThat(failed.getFailure())
                .as("a panel's exception message is uncontrolled text dropped onto an operator's page; the log has it")
                .doesNotContain(SECRET).doesNotContain("secret-key");
    }

    @Test
    void a_panel_that_cannot_even_declare_itself_is_filed_under_its_class_rather_than_dropped() {
        RenderedPanel failed = render(List.of(new UndeclarablePanel())).getFirst();

        assertThat(failed.getId()).as("a class-derived anchor, so the card is still reachable")
                .isEqualTo("undeclarablepanel");
        assertThat(failed.getTitle()).as("a blank title is as unusable as a thrown one")
                .isEqualTo(UndeclarablePanel.class.getName());
        assertThat(failed.isFailed()).isTrue();
    }

    @Test
    void a_panel_answering_null_is_a_failure_rather_than_an_empty_card() {
        // An empty card reads as "nothing to report", which is exactly the wrong thing to tell an operator about a
        // panel that answered nothing at all.
        RenderedPanel failed = render(List.of(new Panel() {
            @Override
            public String id() {
                return "nullish";
            }

            @Override
            public String title() {
                return "Nullish";
            }

            @Override
            public String render(ArtifactStore store) {
                return null;
            }
        })).getFirst();

        assertThat(failed.isFailed()).isTrue();
        assertThat(failed.getId()).isEqualTo("nullish");
    }

    @Test
    void a_console_with_only_healthy_panels_carries_no_failure_marker() {
        assertThat(render(List.of(new HealthyPanel("a", "A"), new HealthyPanel("b", "B"))))
                .extracting(RenderedPanel::getFailure).containsExactly("", "");
    }
}
