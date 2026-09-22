package build.jenesis.repository.deploy.web;

import module java.base;

import build.jenesis.repository.ui.ConsoleModuleProvider;
import build.jenesis.repository.ui.NavEntry;

/**
 * Discovers the deploy screen: one menu entry and one screen, registered through the console's extension seam.
 *
 * <p>It is off unless switched on. Publishing from a browser is a convenience for an evaluator and for the
 * occasional artifact that has no build behind it; the way artifacts arrive in a repository is a build tool, and a
 * deployment that offers an upload form to everyone who can reach the console has widened its write surface by
 * default. So the operator asks for it.
 */
public final class DeployConsoleModule implements ConsoleModuleProvider {

    /** The module's name, its {@code jenreg.} gate, and the namespace its templates resolve under. */
    public static final String NAME = "deploy";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Class<?> configuration() {
        return DeployConfig.class;
    }

    @Override
    public boolean enabledByDefault() {
        return false;
    }

    @Override
    public List<NavEntry> navEntries() {
        // A tenant admin, not any signed-in user: this writes into the tenant's repositories, and the floor should
        // read the same as the act. The screen re-checks rather than trusting the nav to have hidden it.
        return List.of(new NavEntry("Deploy", "/deploy", NavEntry.Access.ADMIN));
    }
}
