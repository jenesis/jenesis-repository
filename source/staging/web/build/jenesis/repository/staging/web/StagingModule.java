package build.jenesis.repository.staging.web;

import build.jenesis.repository.server.kernel.ServerModuleProvider;

/**
 * Announces the staging web adapter to the repository server's {@code ServerModuleProvider} discovery, so the server
 * imports {@link StagingWebConfig} - and with it the staging endpoints - without naming staging anywhere.
 */
public final class StagingModule implements ServerModuleProvider {

    @Override
    public String name() {
        return "staging";
    }

    @Override
    public Class<?> configuration() {
        return StagingWebConfig.class;
    }
}
