package build.jenesis.repository.compliance.web;

import build.jenesis.repository.server.kernel.ServerModuleProvider;

/**
 * Announces the compliance-review web adapter to the repository server's {@code ServerModuleProvider} discovery, so the
 * server imports {@link ComplianceWebConfig} - and with it the quarantine, vulnerability, provenance and VEX endpoints -
 * without naming compliance anywhere. With this module off the path the endpoints simply do not exist and the console
 * hides the panels.
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
