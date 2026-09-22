package build.jenesis.repository.config.web;

import build.jenesis.repository.server.kernel.ServerModuleProvider;

/**
 * Announces the deployment-config management web adapter to the repository server's {@code ServerModuleProvider}
 * discovery, so the server imports {@link ConfigWebConfig} - and with it the settings, repository-definition,
 * format-upstream and upstream-credential endpoints - without naming the config surface anywhere.
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
