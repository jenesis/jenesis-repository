package build.jenesis.repository.format.lifecycle.console;

import module java.base;
import build.jenesis.repository.ui.ConsoleModuleProvider;
import build.jenesis.repository.ui.NavEntry;
import build.jenesis.repository.ui.RepositoryPage;

/**
 * Discovers the Lifecycle page: one page of every repository, under its Lifecycle topic, registered through the
 * console's extension seam. It answers to the API's own switch, {@code jenrepo.lifecycle}, so the page and the
 * endpoint it shares a service with are there or absent together.
 */
public final class LifecycleConsoleModule implements ConsoleModuleProvider {

    /** The module's name, its {@code jenrepo.} gate, and the namespace its templates resolve under. */
    public static final String NAME = "lifecycle";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Class<?> configuration() {
        return LifecycleConsoleConfig.class;
    }

    @Override
    public List<RepositoryPage> repositoryPages() {
        return List.of(new RepositoryPage("Deprecations & yanks", "/lifecycle", NavEntry.Access.USER,
                RepositoryPage.Topic.LIFECYCLE, ""));
    }
}
