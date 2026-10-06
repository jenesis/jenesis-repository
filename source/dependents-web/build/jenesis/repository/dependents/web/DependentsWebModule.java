package build.jenesis.repository.dependents.web;

import build.jenesis.repository.server.kernel.ServerModuleProvider;

/**
 * Contributes {@link DependentsWebConfig}, and with it {@code /api/dependents}, to the server.
 * {@code jenrepo.dependents=false} switches it off as if the module were absent.
 */
public final class DependentsWebModule implements ServerModuleProvider {

    @Override
    public String name() {
        return "dependents";
    }

    @Override
    public Class<?> configuration() {
        return DependentsWebConfig.class;
    }
}
