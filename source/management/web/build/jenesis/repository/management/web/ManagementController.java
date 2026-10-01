package build.jenesis.repository.management.web;

import module java.base;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.QuotaSettingsContributor;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.SettingsEditor;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.CredentialLifetimes;
import build.jenesis.repository.server.spi.RateLimiter;
import build.jenesis.repository.server.spi.RateLimiterProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The credential and authorization management surface - the tenant's credentials, grants, lifetime policy, storage
 * quota, request-rate ceiling, named roles and audit trail - contributed through the {@code ServerModuleProvider} seam:
 * a JSON CRUD over the framework-free {@link Authorization}, resolved per tenant through {@link Repositories}, and the
 * discovered {@link AuditTrail}, the tenant the one the routing answers ({@link RepositoryRouting#tenant}). Every route
 * is under {@code /api/} and gated {@code manage:read} or {@code manage:write} at scope {@code *} by the security chain
 * before it is reached, so this controller decides no authorization. Without a rate-limiting or an audit module those
 * endpoints answer {@code 501}, after the auth check. A privileged mutation writes an audit event.
 */
@RestController
public class ManagementController {

    private final Repositories repositories;
    private final RepositoryRouting routing;
    private final Authorization authorization;
    private final AuditTrail audit;
    private final LiveConfig live;
    private final SettingsEditor editor;
    // Module presence is static for a JVM; resolved once so the rate-limit surface can say "not installed".
    private final boolean rateLimiting = RateLimiterProvider.resolve(key -> null) != RateLimiter.NONE;

    public ManagementController(Repositories repositories, RepositoryRouting routing, Authorization authorization,
                                AuditTrail audit, LiveConfig live, SettingsEditor editor) {
        this.repositories = repositories;
        this.routing = routing;
        this.authorization = authorization;
        this.audit = audit;
        this.live = live;
        this.editor = editor;
    }

    /** Who a request acts as on the audit trail: its tenant and its key's hash. */
    private static SettingsEditor.Actor actor(String tenant, String key) {
        return new SettingsEditor.Actor(tenant, key == null ? "anonymous" : Authorization.hash(key));
    }

    private void audit(String tenant, String key, String action, String target) {
        audit.record(tenant, key == null ? "anonymous" : Authorization.hash(key), action, target);
    }

    // The credential routes are the core's CredentialsController's, each a thin call onto Authorization; the audit row
    // is supplied through CredentialContext, so issuing a credential is implemented once.

    @GetMapping("/api/policy")
    @ResponseBody
    public PolicyView policy(HttpServletRequest http) throws IOException {
        CredentialLifetimes.Policy policy = authorization.lifetimes().policy(routing.tenant(http));
        return new PolicyView(policy.defaultLifetime().toString(),
                policy.maxLifetime() == null ? null : policy.maxLifetime().toString());
    }

    /** Set or clear ({@code null}/blank) the tenant's default and maximum credential lifetimes (ISO-8601 durations). */
    @PutMapping("/api/policy")
    public void setPolicy(@RequestHeader(value = Repositories.KEY, required = false) String key,
                          @RequestBody(required = false) PolicyRequest request,
                          HttpServletRequest http, HttpServletResponse response) throws IOException {
        String tenant = routing.tenant(http);
        authorization.lifetimes().setPolicy(tenant,
                request == null ? null : CredentialLifetimes.lifetime(request.defaultLifetime()),
                request == null ? null : CredentialLifetimes.lifetime(request.maxLifetime()));
        audit(tenant, key, AuditActions.POLICY_SET, "lifetime");
        response.setStatus(200);
    }

    /** The tenant's storage quota: the byte ceiling ({@code 0} when unlimited) and the bytes stored, read from the
     *  tenant's and the deployment's settings documents. */
    @GetMapping("/api/quota")
    @ResponseBody
    public QuotaView quota(HttpServletRequest http) throws IOException {
        String tenant = routing.tenant(http);
        return new QuotaView(repositories.quotaLimit(tenant), repositories.quotaUsed(tenant));
    }

    /** Set ({@code > 0}) or clear ({@code 0}, so the deployment's applies) the tenant's storage quota in bytes, its
     *  {@code tenant-quota} setting, through the settings editor, which records it on the trail. */
    @PutMapping("/api/quota")
    public void setQuota(@RequestHeader(value = Repositories.KEY, required = false) String key,
                         @RequestBody QuotaRequest request,
                         HttpServletRequest http, HttpServletResponse response) throws IOException {
        String tenant = routing.tenant(http);
        long maxBytes = request == null ? 0L : request.maxBytes();
        editor.tenant(tenant, Map.of(QuotaSettingsContributor.KEY, maxBytes == 0 ? "" : Long.toString(maxBytes)),
                false, actor(tenant, key));
        // The usage total is not recomputed here, which would walk every blob of the tenant while the caller waits; the
        // cleanup pass recomputes it, so enforcement runs on the previous total until then and a lowered limit may
        // briefly over-admit.
        response.setStatus(200);
    }

    /** The tenant's request rate ceiling in permits per minute ({@code 0} when the deployment default applies), read
     *  from the tenant's and the deployment's settings documents. */
    @GetMapping("/api/rate-limit")
    @ResponseBody
    public RateLimitView rateLimit(HttpServletRequest http, HttpServletResponse response) throws IOException {
        if (!rateLimiting) {
            respondRateLimitNotInstalled(response);
            return null;
        }
        return new RateLimitView(live.own(routing.tenant(http), RATE_LIMIT).map(own -> Long.parseLong(own.trim()))
                .orElse(0L));
    }

    /** The rate ceiling's key, spelled here since the limiter module declaring it is optional. */
    private static final String RATE_LIMIT = "rate-limit";

    /** Set ({@code > 0}) or clear ({@code 0}) the tenant's request rate ceiling in permits per minute, its
     *  {@code rate-limit} setting, through the settings editor. */
    @PutMapping("/api/rate-limit")
    public void setRateLimit(@RequestHeader(value = Repositories.KEY, required = false) String key,
                             @RequestBody RateLimitRequest request, HttpServletRequest http,
                             HttpServletResponse response) throws IOException {
        if (!rateLimiting) {
            respondRateLimitNotInstalled(response);
            return;
        }
        long permitsPerMinute = request == null ? 0L : request.permitsPerMinute();
        String tenant = routing.tenant(http);
        editor.tenant(tenant, Map.of(RATE_LIMIT, permitsPerMinute == 0 ? "" : Long.toString(permitsPerMinute)),
                false, actor(tenant, key));
        response.setStatus(200);
    }

    /** The tenant's named roles (name to comma-separated tokens): built-in read-only/deploy/admin plus custom ones. */
    @GetMapping("/api/roles")
    @ResponseBody
    public Map<String, String> roles(HttpServletRequest http) throws IOException {
        return authorization.roles().of(routing.tenant(http));
    }

    /** Add or replace a custom role by name from comma-separated tokens. */
    @PutMapping("/api/roles/{name}")
    public void setRole(@PathVariable("name") String name,
                        @RequestHeader(value = Repositories.KEY, required = false) String key,
                        @RequestBody RoleRequest request,
                        HttpServletRequest http, HttpServletResponse response) throws IOException {
        String tenant = routing.tenant(http);
        authorization.roles().set(tenant, name, request.tokens());
        audit(tenant, key, AuditActions.ROLE_SET, name);
        response.setStatus(200);
    }

    @DeleteMapping("/api/roles/{name}")
    public void removeRole(@PathVariable("name") String name,
                           @RequestHeader(value = Repositories.KEY, required = false) String key,
                           HttpServletRequest http, HttpServletResponse response) throws IOException {
        String tenant = routing.tenant(http);
        authorization.roles().remove(tenant, name);
        audit(tenant, key, AuditActions.ROLE_REMOVE, name);
        response.setStatus(200);
    }

    /** The tenant's audit trail, newest first, optionally bounded by ISO-8601 {@code from}/{@code to} and one
     *  {@code action}, and paged: by cursor ({@code after}, the previous answer's {@code Jenesis-Next-Cursor}, absent
     *  on the last page) or by {@code offset}/{@code limit} (default 0/500, limit clamped to 1000). The CSV export
     *  streams the whole trail. */
    @GetMapping("/api/audit")
    @ResponseBody
    public List<AuditView> auditTrail(@RequestParam(name = "from", required = false) String from,
                                      @RequestParam(name = "to", required = false) String to,
                                      @RequestParam(name = "action", required = false) String action,
                                      @RequestParam(name = "offset", defaultValue = "0") int offset,
                                      @RequestParam(name = "after", required = false) String after,
                                      @RequestParam(name = "limit", defaultValue = "500") int limit,
                                      HttpServletRequest http, HttpServletResponse response) throws IOException {
        if (audit == AuditTrail.none()) {
            respondAuditNotInstalled(response);
            return null;
        }
        int size = Math.clamp(limit, 1, 1000);
        String tenant = routing.tenant(http);
        AuditTrail.Page page = after != null && !after.isBlank() || offset <= 0
                ? audit.query(tenant, instant(from), instant(to), action, after, size)
                : audit.query(tenant, instant(from), instant(to), action, offset, size);
        if (page.next() != null) {
            response.setHeader("Jenesis-Next-Cursor", page.next());
        }
        List<AuditView> views = new ArrayList<>();
        for (AuditTrail.Event event : page.events()) {
            views.add(new AuditView(event.at().toString(), event.actor(), event.action(), event.target()));
        }
        return views;
    }

    /** The audit trail as a CSV download, streamed a row at a time through the audit SPI's {@code stream}, so a large
     *  trail exports in flat memory. */
    @GetMapping(value = "/api/audit.csv", produces = "text/csv;charset=UTF-8")
    public void auditCsv(@RequestParam(name = "from", required = false) String from,
                         @RequestParam(name = "to", required = false) String to,
                         @RequestParam(name = "action", required = false) String action,
                         HttpServletRequest http, HttpServletResponse response) throws IOException {
        if (audit == AuditTrail.none()) {
            respondAuditNotInstalled(response);
            return;
        }
        String tenant = routing.tenant(http);
        response.setContentType("text/csv;charset=UTF-8");
        Writer out = response.getWriter();
        out.write("at,actor,action,target\n");
        audit.stream(tenant, instant(from), instant(to), action, event ->
                out.write(csv(event.at().toString()) + ',' + csv(event.actor()) + ',' + csv(event.action()) + ','
                        + csv(event.target()) + '\n'));
    }

    /** With no rate-limiting module installed the ceiling endpoints answer 501: a ceiling would meter nothing. */
    private static void respondRateLimitNotInstalled(HttpServletResponse response) throws IOException {
        response.setStatus(501);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("rate limiting is not installed on this deployment");
    }

    /** With no audit module installed the audit endpoints answer 501, after the auth check so 401/403 still
     *  precede. */
    private static void respondAuditNotInstalled(HttpServletResponse response) throws IOException {
        response.setStatus(501);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("audit is not installed on this deployment");
    }

    /** A blank value is no bound; otherwise an absolute ISO-8601 instant. */
    private static Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value.trim());
    }

    /** Quote a CSV field carrying a comma, quote or newline, and prefix a leading {@code = + - @}, tab or carriage
     *  return with an apostrophe so a spreadsheet does not evaluate it. */
    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        String safe = value.isEmpty() || "=+-@\t\r".indexOf(value.charAt(0)) < 0 ? value : "'" + value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    @ExceptionHandler(IllegalStateException.class)
    public void conflict(HttpServletResponse response) throws IOException {
        response.setStatus(409);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public void badRequest(HttpServletResponse response) throws IOException {
        response.setStatus(400);
    }

    public record RoleRequest(String tokens) {
    }

    public record AuditView(String at, String actor, String action, String target) {
    }

    public record PolicyView(String defaultLifetime, String maxLifetime) {
    }

    public record PolicyRequest(String defaultLifetime, String maxLifetime) {
    }

    public record QuotaView(long maxBytes, long usedBytes) {
    }

    public record QuotaRequest(long maxBytes) {
    }

    public record RateLimitView(long permitsPerMinute) {
    }

    public record RateLimitRequest(long permitsPerMinute) {
    }
}
