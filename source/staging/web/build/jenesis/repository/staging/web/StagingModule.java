package build.jenesis.repository.staging.web;

import build.jenesis.repository.server.kernel.ServerModuleProvider;

/** Contributes {@link StagingWebConfig}, and with it the staging endpoints, to the server. */
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
