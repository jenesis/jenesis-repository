package build.jenesis.repository.format.terraform.web;

import build.jenesis.repository.server.kernel.ServerModuleProvider;

/**
 * Announces the Terraform service-discovery document to the repository server's {@code ServerModuleProvider}
 * discovery, so the server imports {@link TerraformDiscoveryConfig} - and with it the one root-level path this
 * product serves - without naming it anywhere. Toggled off by {@code jenreg.terraform=false} (the {@code Features}
 * convention), it degrades exactly as if the module were absent from the image.
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
