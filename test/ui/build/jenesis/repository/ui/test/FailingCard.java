package build.jenesis.repository.ui.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.ui.ConsoleCard;

import module java.base;

/**
 * A {@code provides}-declared card that always fails, so the booted console in {@code ConsoleE2ETest} (downstream)
 * really serves a page containing a contained card failure. That is the leg a unit test cannot cover: whether the
 * shell <em>renders</em> the failure - the navigation marker, the alert in the card's own place - only shows when a
 * real request meets a real failing card, and a template expression that is only evaluated on failure would otherwise
 * be exercised by nothing until a customer's card threw.
 *
 * <p>Its message deliberately carries text the console must not echo into an operator's page.
 */
public final class FailingCard implements ConsoleCard {

    /** The message the failure carries - the page may name the exception type, never this. */
    public static final String SECRET = "cannot read /srv/store/acme/secret-key.pem";

    @Override
    public String id() {
        return "failingfixture";
    }

    @Override
    public String title() {
        return "Failing fixture";
    }

    @Override
    public String fragment() {
        return "console/cards :: logs";
    }

    /** Fails where a real card's store read would: after it has declared itself, while preparing its value. */
    @Override
    public Object model(ArtifactStore store) throws IOException {
        throw new IOException(SECRET);
    }
}
