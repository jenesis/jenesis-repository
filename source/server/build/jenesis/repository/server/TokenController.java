package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.server.spi.TokenExchange;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/**
 * The token-exchange endpoint, an OAuth 2.0 Token Exchange (RFC 8693) token endpoint. A CI job posts the id-token
 * its platform already issues and, when it matches one of the tenant's trusts (the discovered {@link TokenExchange}
 * module), receives a short-lived key in return - so a pipeline holds no static secret.
 *
 * <p><b>Request.</b> A {@code POST} of {@code application/x-www-form-urlencoded} parameters: {@code grant_type}
 * ({@code urn:ietf:params:oauth:grant-type:token-exchange}), {@code subject_token} (the id-token) and
 * {@code subject_token_type} ({@code urn:ietf:params:oauth:token-type:id_token} or {@code ...:jwt}). The tenant
 * whose trusts judge the token is named by {@code resource}, the URL the client will use the key against - a
 * repository's ({@code https://host/repository/<tenant>/<repository>/}) or the build cache's
 * ({@code https://host/build/<tenant>/}) - which {@link ResourceTenants} reads the tenant from as the deployment's
 * routing does, so a client needs no parameter of this server's own; by {@code audience}, the RFC's logical name of
 * the target, for a client that holds the tenant's name rather than a URL; or by {@code tenant}, in the body or the
 * query string. A request naming none gets the deployment's own.
 * {@link TokenRequest#of} states what each further parameter of the RFC earns. A token travels in the body only:
 * the query string, which servers and proxies log, may carry {@code tenant} and nothing else.
 *
 * <p><b>Response.</b> {@code 200} with {@code access_token} (the key, presented as any key is),
 * {@code issued_token_type} ({@code urn:ietf:params:oauth:token-type:access_token}), {@code token_type}
 * ({@code Bearer}) and {@code expires_in} (seconds). A refusal is {@code 400} with {@code error} and
 * {@code error_description} (RFC 6749 section 5.2): {@code invalid_request} for a malformed request and for a
 * token no trust admits, which RFC 8693 section 2.2.2 gives that code, {@code unsupported_grant_type},
 * {@code invalid_scope} and {@code invalid_target}. Every answer carries {@code Cache-Control: no-store} and
 * {@code Pragma: no-cache}.
 *
 * <p>A deployment carrying no exchange module answers {@code 501}, as every route of a module that is not
 * installed does, with {@code unsupported_grant_type} as its {@code error} - the code the RFC has for a grant the
 * server does not offer. The module is what decides, and this is only where a client reaches it.
 *
 * <p>The route authenticates by the signed token itself rather than by a key, so the security chain leaves it open.
 */
@RestController
public class TokenController {

    private final TokenExchange exchange;
    private final CredentialContext context;
    private final ResourceTenants resources;

    public TokenController(TokenExchange exchange, CredentialContext context, ResourceTenants resources) {
        this.exchange = exchange;
        this.context = context;
        this.resources = resources;
    }

    @PostMapping("/api/token")
    public ResponseEntity<Map<String, Object>> token(@RequestParam MultiValueMap<String, String> parameters,
                                                     HttpServletRequest request) throws IOException {
        if (exchange == TokenExchange.NONE) {
            return refused(501, "unsupported_grant_type", "No token exchange is installed");
        }
        if (!form(request.getContentType())) {
            return refused(400, "invalid_request", "The request is application/x-www-form-urlencoded");
        }
        for (String name : UriComponentsBuilder.newInstance().query(request.getQueryString()).build()
                .getQueryParams().keySet()) {
            if (!UriUtils.decode(name, StandardCharsets.UTF_8).equals("tenant")) {
                return refused(400, "invalid_request", "The query string carries the tenant alone: every other "
                        + "parameter is sent in the request body");
            }
        }
        TokenRequest exchangeable;
        try {
            exchangeable = TokenRequest.of(parameters, resource -> resources.tenant(request, resource));
        } catch (TokenRequest.Refused refusal) {
            return refused(400, refusal.error(), refusal.getMessage());
        }
        String tenant = exchangeable.tenant() == null ? context.defaultTenant() : exchangeable.tenant();
        TokenExchange.Exchanged exchanged = exchange.exchange(tenant, exchangeable.subjectToken());
        if (exchanged == null) {
            return refused(400, "invalid_request", "The subject token matches no trust of the tenant");
        }
        context.audit(tenant, exchanged.key(), "credential.exchange", "oidc:" + exchanged.trust());
        Map<String, Object> issued = new LinkedHashMap<>();
        issued.put("access_token", exchanged.key());
        issued.put("issued_token_type", TokenRequest.ACCESS_TOKEN);
        issued.put("token_type", "Bearer");
        issued.put("expires_in", Math.max(0, Duration.between(Instant.now(), exchanged.expires()).toSeconds()));
        return answer(200, issued);
    }

    private static boolean form(String contentType) {
        try {
            return contentType != null
                    && MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(MediaType.parseMediaType(contentType));
        } catch (InvalidMediaTypeException malformed) {
            return false;
        }
    }

    private static ResponseEntity<Map<String, Object>> refused(int status, String error, String description) {
        Map<String, Object> refusal = new LinkedHashMap<>();
        refusal.put("error", error);
        refusal.put("error_description", description);
        return answer(status, refusal);
    }

    private static ResponseEntity<Map<String, Object>> answer(int status, Map<String, Object> body) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
