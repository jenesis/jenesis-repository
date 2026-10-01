package build.jenesis.repository.console.api;

import build.jenesis.repository.server.kernel.ServerModuleProvider;

/**
 * Announces the console store's API twins to the server's {@code ServerModuleProvider} discovery, so it imports
 * {@link ConsoleApiConfig} - the SCIM-token, cache-project, tenant, origin and folder-children endpoints - naming none.
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
