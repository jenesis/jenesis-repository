package build.jenesis.repository.ui;

import build.jenesis.repository.observation.Contributions;
import build.jenesis.repository.store.ArtifactStore;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import module java.base;

/**
 * Renders the console: it asks each registered {@link Panel} to render its body against the repository
 * {@link ArtifactStore} and drops the fragments into the Thymeleaf shell ({@code templates/console.html}) as a tabbed
 * page, so a plugged-in panel appears with no change here. The panels are the {@code ServiceLoader}-discovered list
 * bridged by {@link UiConfig}.
 *
 * <p>One panel never decides whether the console exists: the render loop runs through {@code Contributions}, so a panel
 * that throws is contained to its own card. It keeps its navigation entry and its anchor, its body is replaced by a
 * visible failure notice naming the panel's class and the kind of failure, the failure is logged once, and every other
 * panel renders exactly as it would have. A failed panel must never read like a panel with nothing to show - that is
 * why the notice takes the body's place rather than the body being left empty.
 */
@Controller
public class ConsoleController {

    private final List<Panel> panels;
    private final ArtifactStore store;

    public ConsoleController(List<Panel> panels, ArtifactStore store) {
        this.panels = panels;
        this.store = store;
    }

    /** The root forwards to the console so a bare host lands on it, keeping {@code /} free of a functional route. */
    @GetMapping("/")
    public String root() {
        return "redirect:/console";
    }

    /** Renders every panel. This declares no {@code throws}: a panel's failure is contained into that panel's card, so
     *  there is no longer any way for one panel to fail this request. */
    @GetMapping("/console")
    public String console(Model model) {
        model.addAttribute("panels", Contributions.collect("console panel", panels,
                panel -> new RenderedPanel(panel.id(), panel.title(), panel.render(store), ""),
                ConsoleController::unavailable));
        return "console";
    }

    /**
     * The card a panel that threw is rendered as: the panel's own id and title where it can still declare them (so the
     * navigation entry and the {@code #id} anchor a bookmark points at survive), an empty body, and a failure notice
     * the shell renders in place of that body. The notice names the panel's implementation class and the exception
     * <em>type</em> only - a panel's exception message is uncontrolled text that may quote an artifact path or a
     * configured value, and this fragment is dropped into an operator's page - so the full failure goes to the log.
     */
    private static RenderedPanel unavailable(Panel panel, Exception failure) {
        String fallbackId = Contributions.segment(panel);
        return new RenderedPanel(
                declaration(panel, Panel::id, fallbackId),
                declaration(panel, Panel::title, panel.getClass().getName()),
                "",
                "This panel failed to render: " + panel.getClass().getName() + " threw "
                        + Contributions.reason(failure) + ". Every other panel is unaffected, and the server log"
                        + " carries the failure - nothing here is a statement about what the panel would have shown.");
    }

    /** A declaration off a panel that has already failed, falling back when it cannot answer or answers blank. */
    private static String declaration(Panel panel, Contributions.Contribution<Panel, String> declaration,
                                      String fallback) {
        String declared = Contributions.declared(panel, declaration, fallback);
        return declared.isBlank() ? fallback : declared;
    }

    /** A panel prepared for the view: its id and title for the navigation, its already-rendered HTML body, and - when
     *  the panel failed - the notice the shell renders instead of that body ({@code ""} for a healthy panel). */
    public static final class RenderedPanel {

        private final String id;
        private final String title;
        private final String body;
        private final String failure;

        RenderedPanel(String id, String title, String body, String failure) {
            // Strict on the way in, and the containment above is why: a panel answering null for its id, title or
            // body throws here, inside the contained call, so it becomes a card that says the panel failed. Left
            // lenient it would reach the shell as a nameless, anchorless or empty card - a hole in the console that
            // reads exactly like a panel with nothing to report.
            this.id = Objects.requireNonNull(id, "panel id");
            this.title = Objects.requireNonNull(title, "panel title");
            this.body = Objects.requireNonNull(body, "panel body");
            this.failure = Objects.requireNonNull(failure, "panel failure");
        }

        public String getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public String getBody() {
            return body;
        }

        /** The failure notice, escaped by the template - empty when the panel rendered. */
        public String getFailure() {
            return failure;
        }

        /** Whether this panel failed to render, so the shell marks it in the navigation and in its card. */
        public boolean isFailed() {
            return !failure.isEmpty();
        }
    }
}
