package build.jenesis.repository.compliance.web;

import build.jenesis.repository.server.kernel.ServerModuleProvider;

/**
 * Announces the compliance-review API to the server's {@code ServerModuleProvider} discovery, so the server imports
 * {@link ComplianceWebConfig} without naming it; without this module the endpoints do not exist.
 */
public final class ComplianceWebModule implements ServerModuleProvider {

    @Override
    public String name() {
        return "compliance";
    }

    @Override
    public Class<?> configuration() {
        return ComplianceWebConfig.class;
    }
}
