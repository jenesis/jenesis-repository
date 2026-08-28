package build.jenesis.repository.ui;

import build.jenesis.repository.store.ArtifactStore;

/**
 * The bundled recent-logs card: the console's window onto the server's {@code GET /api/logs} tail - the bounded
 * in-memory ring a logback appender feeds. Consistent with the downstream console's Logs tab: a level filter, a text
 * search and an auto-tail (a poll that advances the {@code since} cursor), calling the key-auth'd JSON API with the
 * {@code Jenesis-Repository-Key} header (the free console authenticates the human by session, but the server's
 * {@code /api/logs} read is key-gated like every other {@code /api} surface, so the card carries the key the same way
 * the downstream console does). It reads nothing from the {@link ArtifactStore} - the log tail is a live API read, not
 * store state - and degrades gracefully: before a key is entered, or against a deployment whose ring is empty, it shows
 * an empty tail rather than an error.
 *
 * <p>The tail is the browser's read, not this card's (contract clause 7): the fragment is delivered with its controls
 * and an empty output, and {@code /js/cards.js} binds them. So this card prepares no value at all.
 */
public final class LogCard implements ConsoleCard {

    @Override
    public String id() {
        return "logs";
    }

    @Override
    public String title() {
        return "Logs";
    }

    @Override
    public String fragment() {
        return "console/cards :: logs";
    }

    @Override
    public Object model(ArtifactStore store) {
        return null;
    }
}
