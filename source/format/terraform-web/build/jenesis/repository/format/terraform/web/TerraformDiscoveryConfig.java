package build.jenesis.repository.format.terraform.web;

import build.jenesis.repository.scope.Scopes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Terraform service-discovery document as an explicit {@code @Bean}, so no component scan crosses the module
 * boundary; imported through {@link TerraformDiscoveryModule}.
 */
@Configuration(proxyBeanMethods = false)
public class TerraformDiscoveryConfig {

    /** The registry path a deployment serves when it names none: a repository named {@code terraform} in the
     *  default tenant. */
    public static final String DEFAULT_PREFIX = "/repository/" + Scopes.DEFAULT_TENANT + "/terraform/registry";

    /**
     * The path this deployment serves its Terraform registry under, {@link #DEFAULT_PREFIX} unless configured. It
     * cannot be derived: the discovery document is fetched from the host root, so the request names no repository.
     */
    @Bean
    public TerraformDiscoveryController terraformDiscoveryController(
            @Value("${jenrepo.terraform.prefix:" + DEFAULT_PREFIX + "}") String prefix) {
        return new TerraformDiscoveryController(prefix);
    }
}
