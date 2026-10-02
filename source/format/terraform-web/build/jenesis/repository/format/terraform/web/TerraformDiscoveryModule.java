package build.jenesis.repository.format.terraform.web;

import build.jenesis.repository.server.kernel.ServerModuleProvider;

/**
 * Contributes {@link TerraformDiscoveryConfig}, and with it the one root-level path this product serves, to the server.
 * {@code jenrepo.terraform=false} switches it off as if the module were absent.
 */
public final class TerraformDiscoveryModule implements ServerModuleProvider {

    @Override
    public String name() {
        return "terraform";
    }

    @Override
    public Class<?> configuration() {
        return TerraformDiscoveryConfig.class;
    }
}
