package build.jenesis.repository.server;
import module java.base;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.KeyUsageTracker;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.web.util.UriUtils;

/**
 * Authorizes a request against the {@link Authorization} credential model. An anonymous deployment (the
 * {@code jenreg.auth=false} opt-out) allows everything; an enforcing one reads the presented key
 * ({@link PresentedKey}) and requires {@code repository:read} for a GET/HEAD and
 * {@code repository:write} for any other method on the repository the URL names, on the router-resolved
 * in-repository path so a path-scoped grant ({@code <repo>:<prefix>}) authorizes exactly its subtree. The computed
 * {@link Authorization.Decision} is recorded on the request so {@link RepositoryAuthorizationEntryPoint} can answer
 * {@code 401} for an unauthorized request (no key, a malformed or expired key) and {@code 403} for a forbidden one
 * (a key that lacks the right), regardless of which Spring Security failure path the denial takes. It is contributed
 * as a bean by {@link RepositorySecurityAutoConfiguration}.
 */
public class RepositoryAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final Authorization authorization;
    private final KeyUsageTracker usage;

    public RepositoryAuthorizationManager(Authorization authorization) {
        this(authorization, KeyUsageTracker.NONE);
    }

    /**
     * The authorizing manager, recording each accepted credential's use through {@code usage}.
     *
     * <p>This is the only place that knows a request was accepted <em>and</em> which credential accepted it, so it is
     * where a use is recorded. The tracker batches: a busy key costs one store write a day rather than one per
     * request, and with no tracker installed nothing is recorded at all.
     */
    public RepositoryAuthorizationManager(Authorization authorization, KeyUsageTracker usage) {
        this.authorization = authorization;
        this.usage = usage;
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
                                         RequestAuthorizationContext context) {
        if (!authorization.enforced()) {
            return new AuthorizationDecision(true);
        }
        HttpServletRequest request = context.getRequest();
        String method = request.getMethod();
        String required = method.equals("GET") || method.equals("HEAD")
                ? Authorization.REPOSITORY_READ
                : Authorization.REPOSITORY_WRITE;
        // Classify on the percent-DECODED, normalized path Spring actually routes on, not the raw request URI. Spring
        // matches the mapping against the decoded path, so a percent-encoded route (e.g. GET /api/%6cogs, /api/%61ssets)
        // reaches RecentLogsController / the asset enumeration while a raw-URI equals() below would miss it - letting a
        // repository-scoped key evade the deployment-wide "*" rebind (/api/logs, /api/consistency, /api/posture) or the
        // /api/assets ?repo re-scope and read another scope's content. Decode, then reject an un-normalized URI (an empty
        // "//" or dot "/./"/"/.." segment, incl. a %2f/%2e that decodes into one) outright - never a legitimate artifact,
        // /api or /actuator route - so it cannot slip a deployment-wide read past these equals() checks either.
        String uri = UriUtils.decode(request.getRequestURI(), StandardCharsets.UTF_8);
        if (!normalized(uri)) {
            request.setAttribute("jenreg.decision", Authorization.Decision.FORBIDDEN);
            return new AuthorizationDecision(false);
        }
        // No scope, unless the request names one below, is the deployment-wide "*": a route that says nothing about
        // which repository it reads takes a right over all of them. The scope is never a header the caller chose,
        // which would let a key scoped to one repository reach a route that reads another by naming its own.
        String scope = null;
        // An artifact request names its repository in the URL, and that is the repository its right is checked
        // against. The URL is read the way every routing reads it, so a path-scoped grant (<repo>:<prefix>)
        // authorizes exactly the subtree it grants; which tenants a request may address is the routing's to refuse,
        // at the controller. The bare /v2/ names no tenant and no repository: it is the OCI registry's version probe,
        // which asks only whether the credential is accepted. A staged upload names its repository the same way, under
        // its own root.
        boolean artifact = uri.startsWith("/repository/") || uri.startsWith(RepositoryRouting.STAGING)
                || uri.equals("/v2") || uri.startsWith("/v2/");
        boolean probe = uri.equals("/v2") || uri.equals("/v2/");
        RepositoryRouting.Target target = artifact ? RepositoryRouting.target(uri) : null;
        if (artifact) {
            // A URL naming no repository - the registry's own catalog - reads across the tenant's repositories, so it
            // takes a right over all of them.
            scope = target.repository().isEmpty() ? "*" : target.repository();
        }
        // PUT /repository/<tenant>/<name> - the bare repository, no path within it - creates the repository, which is
        // administration rather than a publish: a key that may deploy into a repository may not thereby create
        // repositories, and an administrator's key may create one without holding a deploy right on it.
        if (uri.startsWith("/repository/") && "PUT".equals(method) && target.path().equals("/") && !uri.endsWith("/")) {
            required = Authorization.MANAGE_WRITE;
        }
        // The asset enumeration scopes the store it reads by its ?repo= parameter, so the repository authorized is the
        // one actually enumerated. Read the parameter only for that GET route (never on an upload path, where touching
        // getParameter could drain a form-encoded body). The controller refuses a request without it; the scope is then
        // "*", which only a deployment-wide key holds.
        // An operation on one repository - its cleanup, retention, pins, an import into it, a staged release's
        // promotion - names that repository in its ?repo= parameter and takes its rights, exactly as its artifacts do.
        Optional<String> operated = RepositoryRouting.operated(uri, request.getQueryString());
        if (operated.isPresent()) {
            scope = operated.get();
        }
        if ("/api/assets".equals(uri)) {
            String repo = request.getParameter("repo");
            if (repo != null && !repo.isBlank()) {
                scope = repo;
            }
        }
        // GET /api/logs, GET /api/consistency, GET /api/posture and the /actuator endpoints serve DEPLOYMENT-WIDE
        // content - every repository's / every tenant's log lines (logger names + messages carrying other scopes'
        // coordinates, paths, errors), the whole fleet's per-node consistency state, every tenant's unsafe-setting
        // advisories (each posture row names the tenant, scope and the exact jenreg.* key/value that is unsafe - the
        // deployment's whole security-weakness enumeration, though never a resolved secret value), and the actuator's
        // deployment-wide Micrometer metrics (request counts/URIs/statuses across all repositories, JVM internals) and
        // build info. They are bound to the deployment-wide scope "*", so only a key holding a wildcard grant may read
        // them - a repository-scoped key is refused, since each of them reads every other scope's content. A "*" grant
        // reads the whole view, which is the deployment-observability feature they exist for. The binding is named
        // here rather than left to the default scope because the anonymous rule below keys on the same routes.
        // (The three probe paths - /actuator/health and the liveness/readiness groups - are permit-all in the security
        // chain, so they never reach this manager. Everything else under /actuator does, this binding included: the
        // per-component health paths, the /actuator/health/full group that carries the whole breakdown,
        // /actuator/metrics, /actuator/info and any other exposed actuator endpoint.)
        // The deployment-wide OPERATOR-observability routes: GET/HEAD /api/logs, /api/consistency and the /actuator
        // subtree. /api/posture is deployment-wide too and is bound to "*" alongside them, but it is INTENTIONALLY
        // anonymous-readable (a public advisory, already tested), so it is deliberately kept OUT of this operator set.
        boolean operatorObservability = ("GET".equals(method) || "HEAD".equals(method))
                && ("/api/logs".equals(uri) || "/api/consistency".equals(uri)
                        || "/actuator".equals(uri) || uri.startsWith("/actuator/"));
        // The credential surface administers the TENANT's keys, not one repository's content, so it is bound
        // deployment-wide for the same reason the observability routes are: a repository-scoped key must not mint
        // itself a credential for every other scope.
        // It also takes the manage: rights rather than the repository: ones - issuing a key is administration, and
        // a key that may publish an artifact must not thereby be able to issue more keys.
        boolean credentials = "/api/credentials".equals(uri) || uri.startsWith("/api/credentials/");
        if (credentials) {
            scope = "*";
            required = "GET".equals(method) || "HEAD".equals(method)
                    ? Authorization.MANAGE_READ
                    : Authorization.MANAGE_WRITE;
        }
        if (operatorObservability
                || (("GET".equals(method) || "HEAD".equals(method)) && "/api/posture".equals(uri))) {
            scope = "*";
        }
        String key = PresentedKey.from(request);
        boolean keyless = key == null || key.isBlank();
        String path = artifact ? target.path() : null;
        Authorization.Decision decision;
        try {
            // A key may carry a source-IP allowlist (set-allowed-addresses): a request from an address outside it is
            // forbidden even with an otherwise-valid key, so a stolen key is useless off its network. Enforce it on the
            // request path here - authorize() alone never consults it - deriving the client address the way
            // Authorization.clientAddress documents (the TCP peer, honouring a forwarded header only from a trusted
            // proxy; with no trusted proxies configured a client-set X-Forwarded-For is ignored, so the allowlist
            // cannot be spoofed). A key with no allowlist admits every address, so this is a no-op for the common case.
            if (!authorization.addressAllowed(key, clientAddress(request))) {
                decision = Authorization.Decision.FORBIDDEN;
            } else {
                decision = probe
                        ? authorization.authenticated(key)
                        : authorization.authorize(key, scope, path, required);
            }
        } catch (IOException e) {
            decision = Authorization.Decision.FORBIDDEN;
        }
        // Close an anonymous-grant cross-scope disclosure on the deployment-wide operator-observability routes. When an
        // operator enables the public-mirror opt-in jenreg.anonymous-rights=repository:read, the anonymous
        // grant parses to the WILDCARD scope "*" - exactly what these routes are rebound to above - so a completely
        // KEYLESS caller would satisfy covers("*","*",path) + grantedBy("repository:read") and authorize() ALLOWS it,
        // reading the deployment-wide operator view (the fleet log ring, the whole fleet's consistency state, the
        // actuator metrics) with no key at all. That contradicts the intent stated above: only a key holding a wildcard
        // grant may read them. Refuse the keyless caller here - downgrade that anonymous-grant ALLOWED to FORBIDDEN, so
        // the anonymous wildcard grant can no longer satisfy an operator route. Scoped to keyless + ALLOWED, so every
        // other outcome is untouched: a keyless request on an enforcing deployment WITHOUT the anonymous role is already
        // UNAUTHORIZED (a 401, not ALLOWED) and is left exactly as-is; a present wildcard KEY still reads them; a
        // repository-scoped key is still refused via covers; and the anonymous artifact GET and the intentionally-
        // anonymous /api/posture advisory (outside operatorObservability) are unchanged.
        if (operatorObservability && keyless && decision == Authorization.Decision.ALLOWED) {
            decision = Authorization.Decision.FORBIDDEN;
        }
        request.setAttribute("jenreg.decision", decision);
        String tenant = Authorization.tenantOf(key);
        if (decision == Authorization.Decision.ALLOWED && usage.enabled() && tenant != null) {
            usage.record(tenant, Authorization.hash(key), clientAddress(request));
        }
        return new AuthorizationDecision(decision == Authorization.Decision.ALLOWED);
    }

    /** The client's source address for the allowlist check: the TCP peer, with a forwarded header honoured only from a
     *  trusted proxy. No trusted proxies are configured on the single-token server, so the peer is always the
     *  client and a client-supplied {@code X-Forwarded-For} is ignored (it cannot spoof the allowlist). A deployment
     *  that terminates behind a real proxy contributes a richer manager that passes its trusted-proxy CIDRs here. */
    private static String clientAddress(HttpServletRequest request) {
        return Authorization.clientAddress(
                request.getRemoteAddr(), request.getHeader("X-Forwarded-For"), List.of());
    }

    /** Whether the (already percent-decoded) request path is normalized - carries no empty ({@code //}) or dot
     *  ({@code /.}, {@code /..}) segment. Spring routes on the normalized path, so an un-normalized URI would reach a
     *  controller while the equals()-based scope rebinds above misread it; a legitimate artifact, {@code /api} or
     *  {@code /actuator} route never carries such a segment, so a request that does is rejected rather than classified.
     *  A trailing single slash is left alone - it does not shift an equals() match. Public for a direct unit test. */
    public static boolean normalized(String path) {
        return !path.contains("//")
                && !path.contains("/./") && !path.contains("/../")
                && !path.endsWith("/.") && !path.endsWith("/..");
    }
}
