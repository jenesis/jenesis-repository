package build.jenesis.repository.format.lifecycle.console;

import module java.base;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.format.lifecycle.web.LifecycleMarks;
import build.jenesis.repository.ui.ConsoleModuleProvider;
import build.jenesis.repository.ui.NavEntry;
import build.jenesis.repository.ui.RepositoryPage;

/**
 * Discovers the Lifecycle page: one page of a repository whose format shows its clients a deprecation or a yank, under
 * its Lifecycle topic, registered through the console's extension seam and named for the marks that repository shows.
 * It answers to the API's own switch, {@code jenrepo.lifecycle}, so the page and the endpoint it shares a service with
 * are there or absent together.
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
        return List.of(page(title(EnumSet.allOf(Lifecycle.State.class), "yanked")));
    }

    /** The page named for the marks a repository of {@code type} shows, and none where it shows none. */
    @Override
    public List<RepositoryPage> repositoryPages(String type) {
        Set<Lifecycle.State> shown = LifecycleMarks.states(type);
        return shown.isEmpty() ? List.of() : List.of(page(title(shown, LifecycleMarks.yankName(type))));
    }

    /** What the page is called where a repository shows {@code shown}: the one name its sidebar entry and its
     *  heading share. */
    static String title(Set<Lifecycle.State> shown, String yankName) {
        if (shown.contains(Lifecycle.State.DEPRECATED) && shown.contains(Lifecycle.State.YANKED)) {
            return "Deprecated & " + yankName + " versions";
        }
        return shown.contains(Lifecycle.State.DEPRECATED) ? "Deprecated versions"
                : Character.toUpperCase(yankName.charAt(0)) + yankName.substring(1) + " versions";
    }

    private static RepositoryPage page(String title) {
        return new RepositoryPage(title, "/lifecycle", NavEntry.Access.USER, RepositoryPage.Topic.LIFECYCLE, "");
    }
}
