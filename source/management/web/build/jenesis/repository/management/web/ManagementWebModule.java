package build.jenesis.repository.management.web;

import build.jenesis.repository.server.kernel.ServerModuleProvider;

/**
 * Announces the credential and authorization management web adapter to the repository server's
 * {@code ServerModuleProvider} discovery, so the server imports {@link ManagementWebConfig} - and with it the
 * credential, policy, quota, rate-limit, trust, role and audit endpoints - without naming the management surface
 * anywhere.
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
