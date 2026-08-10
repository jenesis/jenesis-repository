package build.jenesis.repository.ui.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.ui.Panel;

import module java.base;

/**
 * A {@code provides}-declared panel that always fails to render, so the booted console in {@link ConsoleE2ETest} really
 * serves a page containing a contained panel failure. That is the leg a unit test cannot cover: whether the shell
 * <em>renders</em> the failure - the navigation marker, the alert in the panel's own card - only shows when a real
 * request meets a real failing panel, and a template expression that is only evaluated on failure would otherwise be
 * exercised by nothing until a customer's panel threw.
 *
 * <p>Its message deliberately carries text the console must not echo into an operator's page.
 */
public final class FailingPanel implements Panel {

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
    public String render(ArtifactStore store) throws IOException {
        throw new IOException(SECRET);
    }
}
