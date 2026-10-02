package build.jenesis.repository.deploy.web;

import module java.base;

import build.jenesis.repository.ui.ConsoleModuleProvider;
import build.jenesis.repository.ui.NavEntry;
import build.jenesis.repository.ui.RepositoryPage;

/**
 * The deploy screen: a page of every repository, under its contents.
 *
 * <p>Off unless switched on: artifacts arrive through build tools, and an upload form for everyone who can reach the
 * console would widen the write surface by default.
 */
public final class DeployConsoleModule implements ConsoleModuleProvider {

    /** The module's name, its {@code jenrepo.} gate, and the namespace its templates resolve under. */
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
    public List<RepositoryPage> repositoryPages() {
        // A tenant admin, since this writes into the tenant's repositories; the route re-checks rather than trusting
        // the sidebar to have hidden it.
        return List.of(new RepositoryPage("Deploy", "/deploy", NavEntry.Access.ADMIN, RepositoryPage.Topic.CONTENTS,
                ""));
    }
}
