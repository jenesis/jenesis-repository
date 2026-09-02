package build.jenesis.repository.ui;

import module java.base;

import build.jenesis.repository.observation.Contributions;
import build.jenesis.repository.store.ArtifactStore;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Renders the console: it asks each registered {@link ConsoleCard} which fragment renders it and what value that
 * fragment renders against, reading the repository through the {@link ArtifactStore}, and the Thymeleaf shell
 * ({@code templates/console.html}) renders each card in place, so a plugged-in card appears with no change here. The
 * cards are the {@code ServiceLoader}-discovered list bridged by {@link UiConfig}.
 *
 * <p>This controller composes the page and renders none of it. A card used to hand back its body as an HTML string
 * that the shell dropped in unescaped; now it names a fragment, so the engine escapes what it interpolates and the
 * only markup on the page comes from a template.
 *
 * <p>One card never decides whether the console exists: the loop runs through {@code Contributions}, so a card that
 * throws is contained to its own card. It keeps its navigation entry and its anchor, its body is replaced by a
 * visible failure notice naming the card's class and the kind of failure, the failure is logged once, and every other
 * card renders exactly as it would have. A failed card must never read like a card with nothing to show - that is why
 * the notice takes the body's place rather than the body being left empty.
 */
@Controller
public class ConsoleController {

    private final List<ConsoleCard> cards;
    private final ArtifactStore store;

    public ConsoleController(List<ConsoleCard> cards, ArtifactStore store) {
        this.cards = cards;
        this.store = store;
    }

    /** The root forwards to the console so a bare host lands on it, keeping {@code /} free of a functional route. */
    @GetMapping("/")
    public String root() {
        return "redirect:/console";
    }

    /** Prepares every card. This declares no {@code throws}: a card's failure is contained into that card, so there
     *  is no longer any way for one card to fail this request. */
    @GetMapping("/console")
    public String console(Model model) {
        model.addAttribute("cards", Contributions.collect("console card", cards,
                card -> new RenderedCard(card.id(), card.title(), card.fragment(), card.model(store), ""),
                ConsoleController::unavailable));
        return "console";
    }

    /**
     * The card a contribution that threw is rendered as: its own id and title where it can still declare them (so the
     * navigation entry and the {@code #id} anchor a bookmark points at survive), no fragment, and a failure notice the
     * shell renders in place of the body. The notice names the card's implementation class and the exception
     * <em>type</em> only - a card's exception message is uncontrolled text that may quote an artifact path or a
     * configured value, and this reaches an operator's page - so the full failure goes to the log.
     */
    private static RenderedCard unavailable(ConsoleCard card, Exception failure) {
        String fallbackId = Contributions.segment(card);
        return new RenderedCard(
                declaration(card, ConsoleCard::id, fallbackId),
                declaration(card, ConsoleCard::title, card.getClass().getName()),
                "",
                null,
                "This card failed to render: " + card.getClass().getName() + " threw "
                        + Contributions.reason(failure) + ". Every other card is unaffected, and the server log"
                        + " carries the failure - nothing here is a statement about what the card would have shown.");
    }

    /** A declaration off a card that has already failed, falling back when it cannot answer or answers blank. */
    private static String declaration(ConsoleCard card, Contributions.Contribution<ConsoleCard, String> declaration,
                                      String fallback) {
        String declared = Contributions.declared(card, declaration, fallback);
        return declared.isBlank() ? fallback : declared;
    }

    /** A card prepared for the view: its id and title for the navigation, the fragment its body renders through and
     *  the value that fragment reads, and - when the card failed - the notice the shell renders instead of the body
     *  ({@code ""} for a healthy card). */
    public static final class RenderedCard {

        private final String id;
        private final String title;
        private final String fragment;
        private final Object model;
        private final String failure;

        RenderedCard(String id, String title, String fragment, Object model, String failure) {
            // Strict on the way in, and the containment above is why: a card answering null for its id, title or
            // fragment throws here, inside the contained call, so it becomes a card that says the card failed. Left
            // lenient it would reach the shell as a nameless, anchorless or bodiless card - a hole in the console
            // that reads exactly like a card with nothing to report. The model is exempt: null is how a card whose
            // body needs no value says so, which is three of the four bundled ones.
            this.id = Objects.requireNonNull(id, "card id");
            this.title = Objects.requireNonNull(title, "card title");
            this.fragment = Objects.requireNonNull(fragment, "card fragment");
            this.model = model;
            this.failure = Objects.requireNonNull(failure, "card failure");
        }

        public String getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        /** The fragment expression the shell renders this card's body through - {@code ""} for a failed card. */
        public String getFragment() {
            return fragment;
        }

        /** The value that fragment renders against, {@code null} for a body that needs none. */
        public Object getModel() {
            return model;
        }

        /** The failure notice, escaped by the template - empty when the card rendered. */
        public String getFailure() {
            return failure;
        }

        /** Whether this card failed to render, so the shell marks it in the navigation and in its body's place. */
        public boolean isFailed() {
            return !failure.isEmpty();
        }
    }
}
