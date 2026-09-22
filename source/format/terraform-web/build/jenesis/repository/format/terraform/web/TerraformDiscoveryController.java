package build.jenesis.repository.format.terraform.web;

import module java.base;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /.well-known/terraform.json} - the one document a Terraform or OpenTofu client fetches before it knows
 * anything else about a registry.
 *
 * <h2>Why this is not part of the format</h2>
 *
 * <p>Every other surface this product serves lives under a format's own prefix. This one cannot: Terraform derives
 * the discovery URL from the <em>host</em> of a source address - {@code example.com/acme/random} is fetched from
 * {@code https://example.com/.well-known/terraform.json} - and a source address has no room for a path. So the
 * document is served at the server root by a module of its own, which is also what makes it optional: a deployment
 * that leaves this module out still serves both protocols to anything addressing them directly, and is simply not
 * discoverable by {@code terraform init}.
 *
 * <h2>Tenants</h2>
 *
 * <p>The host is not only how Terraform finds the registry - it is also how this product already routes tenants
 * ({@code jenreg.tenant-hosts}). So a deployment mapping {@code acme.example.com} to tenant {@code acme} gets a
 * per-tenant Terraform registry for free: the discovery document is the same for every host, and the requests it
 * points at carry the Host that resolves the tenant. Nothing here needs to know which tenant asked.
 *
 * <p>What the host does <em>not</em> name is the repository, so that is a setting. The real constraint, which is
 * Terraform's and not this product's: <b>one Terraform registry per hostname</b>. A deployment that wants two
 * reachable by {@code terraform init} gives them two hostnames - which is exactly what the tenant mapping already
 * does.
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
     * The service-discovery document.
     *
     * <p>The values are absolute <em>paths</em> rather than absolute URLs, which the protocol allows and which is
     * the right choice here: a path is resolved against the URL the client already used, so a deployment behind a
     * proxy, on a non-default port or reached through any of several hostnames needs no configuration to say so,
     * and cannot be made to point somewhere it is not.
     */
    @GetMapping(path = "/.well-known/terraform.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> discovery() {
        return ResponseEntity.ok("{\"modules.v1\":\"" + prefix + "/v1/modules/\","
                + "\"providers.v1\":\"" + prefix + "/v1/providers/\"}");
    }
}
