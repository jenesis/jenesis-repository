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
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.web.util.UriUtils;

/**
 * Authorizes a request against the {@link Authorization} credential model. An anonymous deployment (the
 * {@code jenreg.auth=false} opt-out) allows everything; an enforcing one reads the presented key
 * ({@link PresentedKey}) and requires a right chosen by what the request addresses and its method:
 *
 * <ul>
 *   <li><b>An artifact</b> - {@code /repository/<tenant>/<repository>/...}, the registry's {@code /v2/...}, a staged
 *       upload - takes {@code repository:read} for a GET or HEAD and {@code repository:write} otherwise, on the
 *       repository the URL names and the path within it, so a path-scoped grant ({@code <repo>:<prefix>})
 *       authorizes exactly its subtree.</li>
 *   <li><b>An operation on one repository</b> - {@code /api/repository/...?repo=<repository>}, its cleanup,
 *       retention, pins, an import into it, a staged release's promotion - takes that repository's rights, exactly as
 *       its artifacts do; an export of it, which sends its contents elsewhere, the manage rights on it.</li>
 *   <li><b>The repository itself</b> - {@code /repository/<tenant>/<name>}, nothing within it, which a {@code PUT}
 *       creates and a {@code DELETE} deletes - takes {@code manage:read} or {@code manage:write} on it.</li>
 *   <li><b>Two reads</b>: the asset enumeration of one repository ({@code /api/assets?repo=}) is that repository's
 *       read, and the installed capabilities ({@code /api/capabilities}) a read at {@code *}.</li>
 *   <li><b>Everything else under {@code /api/}</b>, and the actuator, is administration over the whole deployment:
 *       {@code manage:read} or {@code manage:write} at {@code *}. A key that may publish into every repository - a
 *       CI key - may not thereby change a setting, issue a key, grant a group, purge a module's data or run a walk.
 *       The routes that read or write the deployment rather than a tenant - its settings, repository definitions,
 *       upstreams, the {@code /api/admin/} verbs, the logs, the fleet's consistency and the actuator - further
 *       require a key of the operator tenant ({@code jenreg.operator-tenant}, else {@code default-tenant}): a
 *       tenant administering its own keys cannot repoint an upstream, relax the policy or read every tenant's logs.</li>
 * </ul>
 *
 * <p><b>There was a weaker twin, and this is no longer it.</b> This manager used to take the repository rights on
 * every {@code /api/} route, so on the image that shipped it a key with a wildcard publish right administered the
 * deployment, while a second manager beside it in the other edition's tenancy module held the rules above. The two
 * are one now: the rules are this class's, every composition decides with it, and a richer policy still plugs in
 * through {@link AuthorizationManagerProvider}.
 *
 * <p>The computed {@link Authorization.Decision} is recorded on the request so
 * {@link RepositoryAuthorizationEntryPoint} can answer {@code 401} for an unauthorized request (no key, a malformed
 * or expired key) and {@code 403} for a forbidden one (a key that lacks the right, or whose source address lies
 * outside the credential's allowlist). The source address is the connection peer unless that peer is a configured
 * {@code jenreg.trusted-proxies} hop, in which case it is taken from {@code X-Forwarded-For}, so a client cannot
 * spoof the allowlist by setting the header itself. It is contributed as a bean by
 * {@link RepositorySecurityAutoConfiguration}.
 */
public class RepositoryAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final Authorization authorization;
    private final KeyUsageTracker usage;
    private final List<String> trustedProxies;
    private final String operatorTenant;

    public RepositoryAuthorizationManager(Authorization authorization) {
        this(authorization, KeyUsageTracker.NONE);
    }

    public RepositoryAuthorizationManager(Authorization authorization, KeyUsageTracker usage) {
        this(authorization, usage, new RepositoryProperties());
    }

    /**
     * The authorizing manager, recording each accepted credential's use through {@code usage}, with the operator
     * tenant and the trusted proxies {@code properties} name.
     *
     * <p>This is the only place that knows a request was accepted <em>and</em> which credential accepted it, so it is
     * where a use is recorded. The tracker batches: a busy key costs one store write a day rather than one per
     * request, and with no tracker installed nothing is recorded at all.
     */
    public RepositoryAuthorizationManager(Authorization authorization, KeyUsageTracker usage,
                                          RepositoryProperties properties) {
        this.authorization = authorization;
        this.usage = usage;
        this.trustedProxies = parseTrustedProxies(properties.getTrustedProxies());
        this.operatorTenant = properties.getOperatorTenant().isBlank()
                ? properties.getDefaultTenant()
                : properties.getOperatorTenant();
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
                                         RequestAuthorizationContext context) {
        if (!authorization.enforced()) {
            return new AuthorizationDecision(true);
        }
        HttpServletRequest request = context.getRequest();
        boolean read = request.getMethod().equals("GET") || request.getMethod().equals("HEAD");
        // Classify on the percent-DECODED, normalized path Spring actually routes on, not the raw request URI: a
        // percent-encoded route (/api/%73ettings) must not slip a deployment-global path past the checks below, and an
        // un-normalized one (an empty "//" or dot segment, incl. a %2f/%2e decoding into one) reaches a controller
        // while a prefix check misreads it. Such a URI is never a legitimate artifact, /api or /actuator route.
        String path = UriUtils.decode(request.getRequestURI(), StandardCharsets.UTF_8);
        if (!normalized(path)) {
            request.setAttribute("jenreg.decision", Authorization.Decision.FORBIDDEN);
            return new AuthorizationDecision(false);
        }
        Target target = classify(path, request.getQueryString());
        String required = target.manage()
                ? (read ? Authorization.MANAGE_READ : Authorization.MANAGE_WRITE)
                : (read ? Authorization.REPOSITORY_READ : Authorization.REPOSITORY_WRITE);
        String key = PresentedKey.from(request);
        String client = Authorization.clientAddress(
                request.getRemoteAddr(), request.getHeader("X-Forwarded-For"), trustedProxies);
        Authorization.Decision decision;
        try {
            // A key may carry a source-IP allowlist: a request from outside it is forbidden even with an otherwise
            // valid key, so a stolen key is useless off its network. A key with no allowlist admits every address.
            decision = target.probe()
                    ? authorization.authenticated(key)
                    : authorization.authorize(key, target.scope(), target.subPath(), required);
            if (decision == Authorization.Decision.ALLOWED && !authorization.addressAllowed(key, client)) {
                decision = Authorization.Decision.FORBIDDEN;
            }
        } catch (IOException _) {
            // An unreadable store is no proof of authority: fail closed.
            decision = Authorization.Decision.FORBIDDEN;
        }
        // The operator tenant, for a route that reads or writes the whole deployment. This also refuses a keyless
        // caller there when the public-mirror opt-in grants anonymous rights at "*": no key names the operator tenant.
        if (decision == Authorization.Decision.ALLOWED && global(path)
                && !operatorTenant.equals(Authorization.tenantOf(key))) {
            decision = Authorization.Decision.FORBIDDEN;
        }
        request.setAttribute("jenreg.decision", decision);
        String tenant = Authorization.tenantOf(key);
        if (decision == Authorization.Decision.ALLOWED && usage.enabled() && tenant != null) {
            usage.record(tenant, Authorization.hash(key), client);
        }
        return new AuthorizationDecision(decision == Authorization.Decision.ALLOWED);
    }

    /** Whether a path reads or writes the whole deployment rather than a tenant's own space, so that a manage right
     *  is not enough and the caller must also be the operator tenant. */
    private static boolean global(String path) {
        return path.startsWith("/api/settings") || path.startsWith("/api/repositories")
                || path.startsWith("/api/upstreams") || path.startsWith("/api/admin/")
                || path.equals("/api/logs") || path.startsWith("/api/logs/")
                || path.equals("/api/consistency") || path.startsWith("/api/consistency/")
                || path.equals("/actuator") || path.startsWith("/actuator/");
    }

    /** The authorization target a request path resolves to: the {@code scope} the right is checked against ({@code *}
     *  for administration, else a repository name), the in-repository {@code subPath} a path-prefix grant narrows on
     *  ({@code null} at the repository root), whether it takes the manage rights, and whether the request is the
     *  registry's version probe, which names no repository and asks only whether a credential is accepted. */
    public record Target(String scope, String subPath, boolean manage, boolean probe) {

        public Target(String scope, String subPath, boolean manage) {
            this(scope, subPath, manage, false);
        }
    }

    /** {@link #classify(String, String)} for a request with no query. */
    public static Target classify(String path) {
        return classify(path, null);
    }

    /**
     * Classify a normalized request path, with its {@code query}, into the {@link Target} it authorizes against. An
     * artifact path names its tenant and repository the way every routing reads it ({@link RepositoryRouting#target});
     * the tenant does not enter the scope, because a request's tenant is confined by the routing and the decision runs
     * in the key's own tenant's credential space. Public for a direct unit test of the classification.
     */
    public static Target classify(String path, String query) {
        Optional<String> operated = RepositoryRouting.operated(path, query);
        if (operated.isPresent()) {
            // An export sends the repository's contents and a credential wherever it is told to, so it is
            // administration of that repository rather than a use of it.
            return new Target(operated.get(), null, path.startsWith("/api/repository/export"));
        }
        // Two reads that are not administration. The asset enumeration of one repository is that repository's read:
        // it is what another instance's importer walks to move a repository out, with a key that may only read it.
        // And the capabilities - which modules are installed, what an anonymous caller may do - are a read at *, since
        // a client asks them to tell "not installed" from "not found" and they name nothing a tenant owns.
        Optional<String> enumerated = path.equals("/api/assets") ? parameter(query, "repo") : Optional.empty();
        if (enumerated.isPresent()) {
            return new Target(enumerated.get(), null, false);
        }
        if (path.equals("/api/capabilities")) {
            return new Target("*", null, false);
        }
        if (path.startsWith("/api/") || path.equals("/actuator") || path.startsWith("/actuator/")) {
            return new Target("*", null, true);
        }
        RepositoryRouting.Target target = RepositoryRouting.target(path);
        if (target.repository().isEmpty()) {
            return new Target("*", null, false, target.tenant().isEmpty() && path.startsWith("/v2"));
        }
        String within = target.path().substring(1);
        boolean itself = within.isEmpty() && !path.endsWith("/") && path.startsWith("/repository/");
        return new Target(target.repository(), within.isEmpty() ? null : within, itself);
    }

    /** The one value of {@code name} in {@code query}, when it carries exactly one non-blank value. */
    private static Optional<String> parameter(String query, String name) {
        if (query == null) {
            return Optional.empty();
        }
        String found = null;
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8).equals(name)) {
                if (found != null) {
                    return Optional.empty();
                }
                found = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            }
        }
        return found == null || found.isBlank() ? Optional.empty() : Optional.of(found);
    }

    /** Parse the comma-separated {@code jenreg.trusted-proxies} value into the reverse-proxy CIDRs a forwarded header
     *  is believed from. A malformed entry fails the start naming it rather than being dropped: a swallowed CIDR would
     *  leave the real proxy untrusted and every request appearing to come from it, silently disabling the source-IP
     *  allowlist. Empty is the secure default - no forwarded header is believed. */
    private static List<String> parseTrustedProxies(String configured) {
        List<String> parsed = new ArrayList<>();
        for (String entry : configured.split(",")) {
            String cidr = entry.trim();
            if (cidr.isEmpty()) {
                continue;
            }
            try {
                new IpAddressMatcher(cidr);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Malformed jenreg.trusted-proxies entry '" + cidr
                        + "': expected an IP address or CIDR such as 10.0.0.0/8 or 2001:db8::/32", e);
            }
            parsed.add(cidr);
        }
        return List.copyOf(parsed);
    }

    /** Whether the (already percent-decoded) request path is normalized - carries no empty ({@code //}) or dot
     *  ({@code /.}, {@code /..}) segment. A trailing single slash is left alone. Public for a direct unit test. */
    public static boolean normalized(String path) {
        return !path.contains("//")
                && !path.contains("/./") && !path.contains("/../")
                && !path.endsWith("/.") && !path.endsWith("/..");
    }
}
