package build.jenesis.repository.console.api;

import module java.base;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.ui.store.ScimTokens;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issuing and clearing a tenant's SCIM bearer token over the API: the key-header-authenticated twin of the
 * console's admin screen, minting through the same {@link ScimTokens}.
 *
 * <p><b>Why it exists.</b> Setting up a tenant's identity-provider connector meant clicking a button, so the one
 * step that makes SCIM provisioning work could not be done by the tool that does everything else around it - a
 * deployment scripted end to end still had to stop and open a browser once. That is the gap the surface-parity rule
 * reported, and the reason the product's rule is one capability on three surfaces rather than two.
 *
 * <p><b>The secret is returned once and never again.</b> {@link ScimTokens} stores only the SHA-256 of the token,
 * so this response is the single moment it exists in readable form - exactly as the console's screen says when it
 * shows it. A caller that loses it mints another; there is no read-back, by construction rather than by policy.
 *
 * <p>The tenant's SCIM configuration lives in the console node's segment of the store, so a repository-only
 * composition does not have it and this answers 501 there rather than refusing to start - the same shape its
 * sibling {@link CacheProjectsController} and {@code StagingController} use for a feature that is not installed.
 */
@RestController
public class ScimTokenController {

    private final ObjectProvider<Documents> storage;
    private final AuditTrail audit;
    private final Repositories repositories;

    public ScimTokenController(@Qualifier("rootStorage") ObjectProvider<Documents> storage,
                               AuditTrail audit,
                               Repositories repositories) {
        this.storage = storage;
        this.audit = audit;
        this.repositories = repositories;
    }

    /** Mint a new token, replacing any current one, and return it - the only time it is readable. */
    @PostMapping("/api/scim/token")
    public Map<String, String> mint(@RequestHeader(value = Repositories.KEY, required = false) String key,
                                    HttpServletResponse response) throws IOException {
        ScimTokens tokens = tokens(key, response);
        if (tokens == null) {
            return Map.of();
        }
        String token = tokens.mint();
        record(key, "scim.token.set");
        response.setStatus(201);
        return Map.of("token", token, "shown", "once");
    }

    @PostMapping("/api/scim/token/clear")
    public Map<String, Object> clear(@RequestHeader(value = Repositories.KEY, required = false) String key,
                                     HttpServletResponse response) throws IOException {
        ScimTokens tokens = tokens(key, response);
        if (tokens == null) {
            return Map.of();
        }
        tokens.set(null);
        record(key, "scim.token.clear");
        return Map.of("cleared", true);
    }

    /**
     * The tenant's SCIM configuration, scoped by the tenant the presented key names.
     *
     * <p>Deliberately the root storage rather than the console's {@code tenantStorage}, which is request-scoped
     * around the <em>selected session</em>: that bean throws when nothing is selected, which is every headless
     * call, and would otherwise act on the session's tenant rather than the key's.
     */
    private ScimTokens tokens(String key, HttpServletResponse response) {
        Documents root = storage.getIfAvailable();
        if (root == null) {
            response.setStatus(501);
            return null;
        }
        String tenant = repositories.tenant(key);
        if (!Repositories.valid(tenant)) {
            response.setStatus(400);
            return null;
        }
        return new ScimTokens(root.scope(tenant));
    }

    /** The same action names the console records, so one query over the trail sees both surfaces' rotations. */
    private void record(String key, String action) {
        String tenant = repositories.tenant(key);
        audit.record(tenant, key == null ? "anonymous" : Authorization.hash(key), action, "scim");
    }
}
