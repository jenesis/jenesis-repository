package build.jenesis.repository.format.terraform.web;

import module java.base;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /.well-known/terraform.json} - the one document a Terraform or OpenTofu client fetches before it knows
 * anything else about a registry. Terraform derives its URL from the host of a source address -
 * {@code example.com/acme/random} is discovered at {@code https://example.com/.well-known/terraform.json} - so it is
 * served at the server root, and the registry it points at is a setting rather than anything the request names. That
 * makes one Terraform registry per hostname, which is Terraform's constraint.
 */
@RestController
public class TerraformDiscoveryController {

    private final String prefix;

    /**
     * @param prefix the path this deployment serves its Terraform registry under, without a trailing slash - the
     *               repository and the registry name inside it, which the request Host cannot carry.
     */
    public TerraformDiscoveryController(String prefix) {
        this.prefix = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }

    /**
     * The service-discovery document. Its values are absolute paths rather than URLs, which the protocol allows, so
     * they resolve against the URL the client used and a deployment behind a proxy, on another port or under several
     * hostnames needs no configuration to say so.
     */
    @GetMapping(path = "/.well-known/terraform.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> discovery() {
        return Map.of("modules.v1", prefix + "/v1/modules/", "providers.v1", prefix + "/v1/providers/");
    }
}
