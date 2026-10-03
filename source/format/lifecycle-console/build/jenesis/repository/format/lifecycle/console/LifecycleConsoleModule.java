package build.jenesis.repository.format.lifecycle.console;

import module java.base;
import build.jenesis.repository.format.LifecycleMark;
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
        return List.of(page(title(LifecycleMark.shown(LifecycleMark.values()))));
    }

    /** The page named for the marks a repository of {@code type} shows, and none where it shows none. */
    @Override
    public List<RepositoryPage> repositoryPages(String type) {
        Map<LifecycleMark, String> shown = LifecycleMarks.shown(type);
        return shown.isEmpty() ? List.of() : List.of(page(title(shown)));
    }

    /** What the page is called where a repository shows {@code shown}, each mark by its word, in the marks' order:
     *  the one name its sidebar entry and its heading share. */
    static String title(Map<LifecycleMark, String> shown) {
        String marks = Stream.of(LifecycleMark.values()).filter(shown::containsKey).map(shown::get)
                .collect(Collectors.joining(" & "));
        return Character.toUpperCase(marks.charAt(0)) + marks.substring(1) + " versions";
    }

    private static RepositoryPage page(String title) {
        return new RepositoryPage(title, "/lifecycle", NavEntry.Access.USER, RepositoryPage.Topic.LIFECYCLE, "");
    }
}
