package build.jenesis.repository.management.web;

import build.jenesis.repository.server.kernel.ServerModuleProvider;

/**
 * Announces the management web adapter to the server's {@code ServerModuleProvider} discovery, so the server imports
 * {@link ManagementWebConfig} without naming the management surface.
 */
public final class ManagementWebModule implements ServerModuleProvider {

    @Override
    public String name() {
        return "management";
    }

    @Override
    public Class<?> configuration() {
        return ManagementWebConfig.class;
    }
}
