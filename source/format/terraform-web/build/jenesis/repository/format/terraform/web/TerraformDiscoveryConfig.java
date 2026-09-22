package build.jenesis.repository.format.terraform.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Terraform service-discovery document into the repository server, registered as an explicit
 * {@code @Bean} so no component scan crosses the module boundary. Imported through {@code ServerModuleProvider}
 * discovery (see {@link TerraformDiscoveryModule}), never named by the server - so with this module absent the
 * server carries no {@code /.well-known/terraform.json} and is simply not discoverable by {@code terraform init}.
 */
@Configuration(proxyBeanMethods = false)
public class TerraformDiscoveryConfig {

    /**
     * The path this deployment serves its Terraform registry under.
     *
     * <p>It has to be configured rather than derived: the discovery document is fetched from the host root, so the
     * request that asks for it carries no repository segment to infer one from. The default is the single-repository
     * shape a deployment has out of the box.
     */
    @Bean
    public TerraformDiscoveryController terraformDiscoveryController(
            @Value("${jenreg.terraform.prefix:/repository/terraform/registry}") String prefix) {
        return new TerraformDiscoveryController(prefix);
    }
}
