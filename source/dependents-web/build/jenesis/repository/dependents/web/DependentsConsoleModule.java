package build.jenesis.repository.dependents.web;

import module java.base;
import build.jenesis.repository.ui.RepositoryPage;
import build.jenesis.repository.ui.ConsoleModuleProvider;

/** This feature's console surface, contributed through the console's seam so the console learns none of its
 *  vocabulary: without this module the screen is simply not there. */
public final class DependentsConsoleModule implements ConsoleModuleProvider {

    @Override
    public String name() {
        return "dependents";
    }

    @Override
    public Class<?> configuration() {
        return DependentsConsoleConfig.class;
    }

    /** Who depends on what in every repository, where the dependents index that answers it is present. */
    @Override
    public List<RepositoryPage> repositoryPages() {
        return List.of(new RepositoryPage("Dependents", "/dependents", RepositoryPage.Topic.PROVENANCE, "dependents"));
    }
}
