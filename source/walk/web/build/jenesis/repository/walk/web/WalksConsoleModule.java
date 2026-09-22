package build.jenesis.repository.walk.web;

import module java.base;

import build.jenesis.repository.ui.ConsoleModuleProvider;
import build.jenesis.repository.ui.NavEntry;

/** The walks screen as a console module: its gate, its configuration and its menu entry. */
public final class WalksConsoleModule implements ConsoleModuleProvider {

    /** The module's name: its {@code jenreg.} gate and the namespace its templates resolve under - not
     *  {@code walks}, which is the document the screen edits. */
    public static final String NAME = "walks-screen";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Class<?> configuration() {
        return WalksConfig.class;
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    @Override
    public List<NavEntry> navEntries() {
        // Deployment-wide, like the settings it edits: the walks document is one document for every tenant, and a
        // walk it schedules reads every tenant's store.
        return List.of(new NavEntry("Walks", "/walks", NavEntry.Access.SUPERADMIN));
    }
}
