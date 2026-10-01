package build.jenesis.repository.cleanup.web;

import build.jenesis.repository.server.kernel.ServerModuleProvider;

/**
 * Announces the repository-maintenance web adapter to the server's {@code ServerModuleProvider} discovery, so it
 * imports {@link MaintenanceWebConfig} - the retention, cleanup and pin endpoints - without naming them; off the path,
 * the endpoints do not exist and the console hides the panels.
 */
public final class MaintenanceWebModule implements ServerModuleProvider {

    @Override
    public String name() {
        return "maintenance";
    }

    @Override
    public Class<?> configuration() {
        return MaintenanceWebConfig.class;
    }
}
