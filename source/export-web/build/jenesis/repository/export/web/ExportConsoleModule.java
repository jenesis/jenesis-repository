package build.jenesis.repository.export.web;

import module java.base;

import build.jenesis.repository.ui.ConsoleModuleProvider;
import build.jenesis.repository.ui.NavEntry;
import build.jenesis.repository.ui.RepositoryPage;

/**
 * Discovers the export screen: one menu entry and one screen, registered through the console's extension seam.
 *
 * <p>On wherever the export module is, as the API it shares a service with is: leaving the product is never behind a
 * switch. It is a tenant admin's screen, since an export sends a repository's contents wherever it is told to.
 */
public final class ExportConsoleModule implements ConsoleModuleProvider {

    /** The module's name, its {@code jenreg.} gate, and the namespace its templates resolve under. */
    public static final String NAME = "export";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Class<?> configuration() {
        return ExportConsoleConfig.class;
    }

    @Override
    public List<RepositoryPage> repositoryPages() {
        // Where a repository's content is sent, beside the forwarding that sends it continuously.
        return List.of(new RepositoryPage("Export", "/export", NavEntry.Access.ADMIN,
                RepositoryPage.Topic.LIFECYCLE, ""));
    }
}
