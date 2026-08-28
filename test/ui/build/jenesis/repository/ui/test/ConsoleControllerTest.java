package build.jenesis.repository.ui.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.ui.ConsoleCard;
import build.jenesis.repository.ui.ConsoleController;
import build.jenesis.repository.ui.ConsoleController.RenderedCard;
import org.springframework.ui.ExtendedModelMap;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the console does with a card that throws. One card must never decide whether the console exists: the loop
 * contains a card's failure to that card, so every other card renders, the failed one keeps its navigation entry and
 * its anchor, and its place says it failed rather than looking like a card with nothing to show.
 *
 * <p>The cards here read no artifact data, so a null store is fine (the pattern the live-API card tests already use).
 */
class ConsoleControllerTest {

    /** The message a failing card's exception carries - uncontrolled text the console must not render. */
    private static final String SECRET = "cannot read /srv/store/acme/secret-key.pem";

    private record HealthyCard(String id, String title) implements ConsoleCard {

        @Override
        public String fragment() {
            return "console/cards :: " + id;
        }

        @Override
        public Object model(ArtifactStore store) {
            return id + " model";
        }
    }

    /** A card that declares itself but cannot prepare its value - the common case (a store read that failed). */
    private record BrokenCard(String id, String title) implements ConsoleCard {

        @Override
        public String fragment() {
            return "console/cards :: " + id;
        }

        @Override
        public Object model(ArtifactStore store) throws IOException {
            throw new IOException(SECRET);
        }
    }

    /** A card that cannot even declare itself: nothing about it is usable, and it still may not 500 the console. */
    private static final class UndeclarableCard implements ConsoleCard {

        @Override
        public String id() {
            throw new IllegalStateException("no id");
        }

        @Override
        public String title() {
            return "  ";
        }

        @Override
        public String fragment() {
            throw new IllegalStateException("no fragment");
        }

        @Override
        public Object model(ArtifactStore store) {
            throw new IllegalStateException("no model");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<RenderedCard> render(List<ConsoleCard> cards) {
        ExtendedModelMap model = new ExtendedModelMap();
        // Before containment this loop propagated, so a single throwing card made GET /console a 500 - and this call
        // threw right here.
        assertThat(new ConsoleController(cards, null).console(model)).isEqualTo("console");
        return (List<RenderedCard>) model.getAttribute("cards");
    }

    @Test
    void one_failing_card_neither_ends_the_page_nor_hides_the_cards_around_it() {
        List<RenderedCard> rendered = render(List.of(
                new HealthyCard("first", "First"),
                new BrokenCard("broken", "Broken"),
                new HealthyCard("last", "Last")));

        assertThat(rendered).extracting(RenderedCard::getId)
                .as("every card keeps its place; a failed card that vanished would read as one with nothing to say")
                .containsExactly("first", "broken", "last");
        assertThat(rendered).extracting(RenderedCard::getModel)
                .as("both healthy cards prepared their real values, and the failed one prepared none")
                .containsExactly("first model", null, "last model");
        assertThat(rendered).extracting(RenderedCard::isFailed).containsExactly(false, true, false);
    }

    @Test
    void the_failed_card_keeps_its_navigation_entry_and_says_why_its_place_is_empty() {
        RenderedCard failed = render(List.of(new BrokenCard("broken", "Broken"))).getFirst();

        assertThat(failed.getId()).as("the anchor a bookmark points at survives the failure").isEqualTo("broken");
        assertThat(failed.getTitle()).as("and so does the navigation title").isEqualTo("Broken");
        assertThat(failed.getFragment())
                .as("no fragment is named, so a half-prepared body cannot be resolved and rendered").isEmpty();
        assertThat(failed.getFailure())
                .as("the notice names the card that failed and the kind of failure")
                .contains(BrokenCard.class.getName()).contains("IOException")
                .contains("Every other card is unaffected");
        assertThat(failed.getFailure())
                .as("a card's exception message is uncontrolled text dropped onto an operator's page; the log has it")
                .doesNotContain(SECRET).doesNotContain("secret-key");
    }

    @Test
    void a_card_that_cannot_even_declare_itself_is_filed_under_its_class_rather_than_dropped() {
        RenderedCard failed = render(List.of(new UndeclarableCard())).getFirst();

        assertThat(failed.getId()).as("a class-derived anchor, so the card is still reachable")
                .isEqualTo("undeclarablecard");
        assertThat(failed.getTitle()).as("a blank title is as unusable as a thrown one")
                .isEqualTo(UndeclarableCard.class.getName());
        assertThat(failed.isFailed()).isTrue();
    }

    @Test
    void a_card_naming_no_fragment_is_a_failure_rather_than_an_empty_card() {
        // An empty card reads as "nothing to report", which is exactly the wrong thing to tell an operator about a
        // card that named nothing to render at all. A null MODEL is legal - three of the four bundled cards answer
        // one - so the strictness has to sit on the fragment, which is the field that decides whether there is a
        // body to resolve.
        RenderedCard failed = render(List.of(new ConsoleCard() {
            @Override
            public String id() {
                return "nullish";
            }

            @Override
            public String title() {
                return "Nullish";
            }

            @Override
            public String fragment() {
                return null;
            }

            @Override
            public Object model(ArtifactStore store) {
                return null;
            }
        })).getFirst();

        assertThat(failed.isFailed()).isTrue();
        assertThat(failed.getId()).isEqualTo("nullish");
    }

    @Test
    void a_card_needing_no_value_is_not_treated_as_one_that_failed() {
        // The logs, consistency and credentials cards all answer null: their bodies are controls the browser binds,
        // so there is nothing for the fragment to read. Reading that as a failure would mark three of the four
        // bundled cards broken on every render.
        RenderedCard rendered = render(List.of(new ConsoleCard() {
            @Override
            public String id() {
                return "valueless";
            }

            @Override
            public String title() {
                return "Valueless";
            }

            @Override
            public String fragment() {
                return "console/cards :: logs";
            }

            @Override
            public Object model(ArtifactStore store) {
                return null;
            }
        })).getFirst();

        assertThat(rendered.isFailed()).isFalse();
        assertThat(rendered.getFragment()).isEqualTo("console/cards :: logs");
        assertThat(rendered.getModel()).isNull();
    }

    @Test
    void a_console_with_only_healthy_cards_carries_no_failure_marker() {
        assertThat(render(List.of(new HealthyCard("a", "A"), new HealthyCard("b", "B"))))
                .extracting(RenderedCard::getFailure).containsExactly("", "");
    }
}
