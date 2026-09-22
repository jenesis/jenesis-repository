package build.jenesis.repository.cleanup.web;

import build.jenesis.repository.server.kernel.ServerModuleProvider;

/**
 * Announces the repository-maintenance web adapter to the repository server's {@code ServerModuleProvider} discovery,
 * so the server imports {@link MaintenanceWebConfig} - and with it the retention, cleanup and pin endpoints - without
 * naming maintenance anywhere. With this module off the path the endpoints simply do not exist and the console hides
 * the panels.
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
