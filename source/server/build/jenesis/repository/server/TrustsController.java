package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.server.spi.Authorization;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * A tenant's workload trusts: each one exchanges a matching id-token for a short-lived credential at
 * {@code /api/token}.
 *
 * <p>It sits beside that endpoint rather than with the rest of a deployment's administration, because a trust is
 * not useful without it and the endpoint is not usable without a trust - an exchange with nothing to match against
 * refuses everything. The credential context supplies the tenant and records the change, so a deployment with an
 * audit ledger gets a row and one without gets the same routes.
 */
@RestController
public class TrustsController {

    /** What a trust change is recorded as. Named here because this is the surface that writes it, so a console
     *  or a client elsewhere records the same act under the same name by referencing these rather than retyping
     *  them - the trail is queried by action, and two spellings is a query that quietly comes back short. */
    public static final String SET = "trust.set", REMOVE = "trust.remove";

    private final Authorization authorization;
    private final CredentialContext context;

    public TrustsController(Authorization authorization, CredentialContext context) {
        this.authorization = authorization;
        this.context = context;
    }

    @GetMapping("/api/trusts")
    public List<TrustView> trusts(@RequestHeader(value = PresentedKey.HEADER, required = false) String key)
            throws IOException {
        List<TrustView> views = new ArrayList<>();
        for (Authorization.Trust trust : authorization.trusts(context.tenant(key))) {
            views.add(new TrustView(trust.name(), trust.issuer(), trust.audience(), trust.subject(),
                    trust.scope(), trust.rights(), trust.ttl() == null ? null : trust.ttl().toString()));
        }
        return views;
    }

    /** Add or replace a trust by name; issuer, scope and rights are required. */
    @PutMapping("/api/trusts/{name}")
    public void setTrust(@PathVariable("name") String name,
                         @RequestHeader(value = PresentedKey.HEADER, required = false) String key,
                         @RequestBody TrustRequest request,
                         HttpServletResponse response) throws IOException {
        authorization.setTrust(context.tenant(key), new Authorization.Trust(name, request.issuer(),
                request.audience(), request.subject(), request.scope(), request.rights(),
                Authorization.lifetime(request.ttl())));
        context.audit(key, SET, name);
        response.setStatus(200);
    }

    @DeleteMapping("/api/trusts/{name}")
    public void removeTrust(@PathVariable("name") String name,
                            @RequestHeader(value = PresentedKey.HEADER, required = false) String key,
                            HttpServletResponse response) throws IOException {
        authorization.removeTrust(context.tenant(key), name);
        context.audit(key, REMOVE, name);
        response.setStatus(200);
    }

    /** One trust as it is read back; the ttl is its ISO-8601 form, or null for the deployment's default. */
    public record TrustView(String name, String issuer, String audience, String subject, String scope, String rights,
                            String ttl) {
    }

    /** A trust as it is written. */
    public record TrustRequest(String issuer, String audience, String subject, String scope, String rights,
                               String ttl) {
    }
}
