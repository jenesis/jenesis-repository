package build.jenesis.repository.config.web;

import build.jenesis.repository.server.kernel.ServerModuleProvider;

/**
 * Announces the deployment-config web adapter to the server's {@code ServerModuleProvider} discovery, so it imports
 * {@link ConfigWebConfig} - the settings, repository-definition, format-upstream and upstream-credential endpoints -
 * without naming them.
 */
public final class ConfigWebModule implements ServerModuleProvider {

    @Override
    public String name() {
        return "config";
    }

    @Override
    public Class<?> configuration() {
        return ConfigWebConfig.class;
    }
}
