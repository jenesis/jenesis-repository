package build.jenesis.repository.search.web;

import module java.base;
import build.jenesis.repository.ui.RepositoryPage;
import build.jenesis.repository.ui.ConsoleModuleProvider;

/**
 * This feature's console surface, contributed through the console's seam; without this module the screen is not there.
 */
public final class SearchConsoleModule implements ConsoleModuleProvider {

    @Override
    public String name() {
        return "search";
    }

    @Override
    public Class<?> configuration() {
        return SearchConsoleConfig.class;
    }

    /** The licence inventory of every repository. */
    @Override
    public List<RepositoryPage> repositoryPages() {
        return List.of(new RepositoryPage("Licenses", "/licenses", RepositoryPage.Topic.RISK, "search"));
    }
}
