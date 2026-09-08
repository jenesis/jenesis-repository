package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.server.spi.TokenExchange;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The token-exchange endpoint. A CI job posts the id-token its platform already issues and, when it matches one of the
 * tenant's trusts (the discovered {@link TokenExchange} module), receives a short-lived key in return - so a pipeline
 * holds no static secret. The token is the body or the {@code Authorization: Bearer} header; the tenant is the
 * {@code tenant} parameter, defaulting to the deployment's own. A token matching no trust gets {@code 401}, and a
 * deployment carrying no exchange module gets {@code 501} - which is what the endpoint is for: the module is what
 * decides, and this is only where a client reaches it.
 *
 * <p>The route authenticates by the signed token itself rather than by a key, so the security chain leaves it open.
 */
@RestController
public class TokenController {

    private final TokenExchange exchange;
    private final CredentialContext context;

    public TokenController(TokenExchange exchange, CredentialContext context) {
        this.exchange = exchange;
        this.context = context;
    }

    @PostMapping("/api/token")
    public ResponseEntity<Map<String, Object>> token(
            @RequestParam(name = "tenant", required = false) String tenant,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) String body) throws IOException {
        if (exchange == TokenExchange.NONE) {
            return ResponseEntity.status(501).build();
        }
        String resolved = tenant == null || tenant.isBlank() ? context.defaultTenant() : tenant;
        // The tenant scopes the credential store this reads and, on a trusted match, mints into. A client supplies it,
        // so a traversal-unsafe name is refused before it can reach a store key, even though a write only follows a
        // validated trust.
        if (!Scopes.valid(resolved)) {
            return ResponseEntity.status(400).build();
        }
        TokenExchange.Exchanged exchanged = exchange.exchange(resolved, bearer(authorization, body));
        if (exchanged == null) {
            return ResponseEntity.status(401).build();
        }
        context.audit(exchanged.key(), "credential.exchange", "oidc:" + exchanged.trust());
        return ResponseEntity.ok(Map.of(
                "key", exchanged.key(),
                "expires", exchanged.expires().toString(),
                "expires_in", Duration.between(Instant.now(), exchanged.expires()).toSeconds()));
    }

    private static String bearer(String authorization, String body) {
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7).trim();
        }
        return body == null ? null : body.trim();
    }
}
