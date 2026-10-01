package build.jenesis.repository.console.api;

import module java.base;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.ui.store.ScimTokens;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issuing and clearing a tenant's SCIM bearer token over the API: the key-header twin of the console's admin screen,
 * minting through the same {@link ScimTokens}, so an identity-provider connector can be set up by script.
 *
 * <p><b>The secret is returned once.</b> {@link ScimTokens} stores only its SHA-256, so this response is the one moment
 * it is readable; a caller that loses it mints another.
 *
 * <p>The tenant's SCIM configuration lives in the console node's segment of the store, so a repository-only composition
 * answers {@code 501} here rather than refusing to start.
 */
@RestController
public class ScimTokenController {

    private final ObjectProvider<Documents> storage;
    private final AuditTrail audit;
    private final RepositoryRouting routing;

    public ScimTokenController(@Qualifier("rootStorage") ObjectProvider<Documents> storage,
                               AuditTrail audit,
                               RepositoryRouting routing) {
        this.storage = storage;
        this.audit = audit;
        this.routing = routing;
    }

    /** Mint a new token, replacing any current one, and return it - the only time it is readable. */
    @PostMapping("/api/scim/token")
    public Map<String, String> mint(@RequestHeader(value = Repositories.KEY, required = false) String key,
                                    HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tenant = routing.tenant(request);
        ScimTokens tokens = tokens(tenant, response);
        if (tokens == null) {
            return Map.of();
        }
        String token = tokens.mint();
        record(tenant, key, "scim.token.set");
        response.setStatus(201);
        return Map.of("token", token, "shown", "once");
    }

    @PostMapping("/api/scim/token/clear")
    public Map<String, Object> clear(@RequestHeader(value = Repositories.KEY, required = false) String key,
                                     HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tenant = routing.tenant(request);
        ScimTokens tokens = tokens(tenant, response);
        if (tokens == null) {
            return Map.of();
        }
        tokens.set(null);
        record(tenant, key, "scim.token.clear");
        return Map.of("cleared", true);
    }

    /** The tenant's SCIM configuration, scoped by the tenant the routing answers for the request - the root storage,
     *  not the console's session-scoped {@code tenantStorage}, which a headless call cannot use and which would name
     *  the session's tenant. */
    private ScimTokens tokens(String tenant, HttpServletResponse response) {
        Documents root = storage.getIfAvailable();
        if (root == null) {
            response.setStatus(501);
            return null;
        }
        if (!Repositories.valid(tenant)) {
            response.setStatus(400);
            return null;
        }
        return new ScimTokens(root.scope(tenant));
    }

    /** The action names the console records, so one query over the trail sees both surfaces' rotations. */
    private void record(String tenant, String key, String action) {
        audit.record(tenant, key == null ? "anonymous" : Authorization.hash(key), action, "scim");
    }
}
