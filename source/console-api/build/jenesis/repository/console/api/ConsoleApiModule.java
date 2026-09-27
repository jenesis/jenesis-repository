package build.jenesis.repository.console.api;

import build.jenesis.repository.server.kernel.ServerModuleProvider;

/**
 * Announces the console store's API twins to the repository server's {@code ServerModuleProvider} discovery, so the
 * server imports {@link ConsoleApiConfig} - and with it the SCIM-token, cache-project, origin and folder-children
 * endpoints - without naming any of them.
 */
public final class ConsoleApiModule implements ServerModuleProvider {

    @Override
    public String name() {
        return "console-api";
    }

    @Override
    public Class<?> configuration() {
        return ConsoleApiConfig.class;
    }
}
