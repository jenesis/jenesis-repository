package build.jenesis.repository.management.web;

import module java.base;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.RateLimiter;
import build.jenesis.repository.server.spi.RateLimiterProvider;
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
 * quota, request-rate ceiling, named roles and the audit trail - peeled out of the
 * {@code RepositoryController} monolith into its own thin {@code web} adapter and contributed through the
 * {@code ServerModuleProvider} seam. A JSON CRUD over the framework-free {@link Authorization} (resolved per tenant
 * through {@link Repositories}) and the discovered {@link AuditTrail}; the tenant is the one carried by
 * the managing {@code Jenesis-Repository-Key} header. Every route here is under {@code /api/} and is gated
 * {@code manage:read} (the reads) or {@code manage:write} (the mutations) at scope {@code *} by the security chain
 * before the request is reached, so this controller makes no authorization decision - the same guard the monolith
 * carried, unchanged by the move. With no rate-limiting module installed the rate-limit endpoints answer {@code 501};
 * with no audit module installed the audit endpoints answer {@code 501}, after the auth check so {@code 401}/{@code 403}
 * still precede. A privileged mutation writes an audit event.
 */
@RestController
public class ManagementController {

    private final Repositories repositories;
    private final Authorization authorization;
    private final AuditTrail audit;
    // Module presence is static for a JVM; resolved once so the rate-limit surface can say "not installed".
    private final boolean rateLimiting = RateLimiterProvider.resolve(key -> null) != RateLimiter.NONE;

    public ManagementController(Repositories repositories, Authorization authorization, AuditTrail audit) {
        this.repositories = repositories;
        this.authorization = authorization;
        this.audit = audit;
    }

    private void audit(String key, String action, String target) {
        String tenant = repositories.tenant(key);
        audit.record(tenant, key == null ? "anonymous" : Authorization.hash(key), action, target);
    }

    // The credential routes are the CORE's: build.jenesis.repository.server.CredentialsController owns list,
    // mint, grant, revoke, expiry, rotate and the source-IP allowlist, and every one of them was already a thin
    // call onto Authorization, which holds the logic. They used to be restated here purely to resolve the tenant
    // through Repositories and to write an audit row - neither of which is logic - so both are supplied through
    // CredentialContext instead and the routes exist once. Two implementations of "issue a credential" would
    // drift, and the drift would be in an authorization surface.

    @GetMapping("/api/policy")
    @ResponseBody
    public PolicyView policy(@RequestHeader(value = Repositories.KEY, required = false) String key) throws IOException {
        Authorization.Policy policy = authorization.policy(repositories.tenant(key));
        return new PolicyView(policy.defaultLifetime().toString(),
                policy.maxLifetime() == null ? null : policy.maxLifetime().toString());
    }

    /** Set or clear ({@code null}/blank) the tenant's default and maximum credential lifetimes (ISO-8601 durations). */
    @PutMapping("/api/policy")
    public void setPolicy(@RequestHeader(value = Repositories.KEY, required = false) String key,
                          @RequestBody(required = false) PolicyRequest request,
                          HttpServletResponse response) throws IOException {
        authorization.setPolicy(repositories.tenant(key),
                request == null ? null : Authorization.lifetime(request.defaultLifetime()),
                request == null ? null : Authorization.lifetime(request.maxLifetime()));
        audit(key, AuditActions.POLICY_SET, "lifetime");
        response.setStatus(200);
    }

    /** The tenant's storage quota: the byte ceiling ({@code 0} when unlimited) and the bytes currently stored. */
    @GetMapping("/api/quota")
    @ResponseBody
    public QuotaView quota(@RequestHeader(value = Repositories.KEY, required = false) String key) throws IOException {
        String tenant = repositories.tenant(key);
        return new QuotaView(repositories.quotaLimit(tenant), repositories.quotaUsed(tenant));
    }

    /** Set ({@code > 0}) or clear ({@code 0}) the tenant's storage quota in bytes, then recount stored usage so the
     *  new ceiling starts from the truth. */
    @PutMapping("/api/quota")
    public void setQuota(@RequestHeader(value = Repositories.KEY, required = false) String key,
                         @RequestBody QuotaRequest request,
                         HttpServletResponse response) throws IOException {
        String tenant = repositories.tenant(key);
        authorization.setQuota(tenant, request == null ? 0L : request.maxBytes());
        // The usage total is NOT recomputed here. It used to be, and that walked every blob of every repository the
        // tenant owns while the caller waited - so the cost of setting a limit grew with the tenant, which is the
        // one thing a request must not do (&sect;10). The cleanup pass already recomputes it for any tenant that has
        // a limit, so deferring costs a window rather than the number: enforcement runs on the previous total until
        // the next pass, and a limit lowered mid-window can be briefly over-admitted against. That is the trade,
        // taken deliberately, and it is the reason the pass runs unconditionally rather than only on change.
        audit(key, AuditActions.QUOTA_SET, Long.toString(request == null ? 0L : request.maxBytes()));
        response.setStatus(200);
    }

    /** The tenant's request rate ceiling in permits per minute ({@code 0} when it falls back to the deployment default). */
    @GetMapping("/api/rate-limit")
    @ResponseBody
    public RateLimitView rateLimit(@RequestHeader(value = Repositories.KEY, required = false) String key,
                                   HttpServletResponse response) throws IOException {
        if (!rateLimiting) {
            respondRateLimitNotInstalled(response);
            return null;
        }
        return new RateLimitView(authorization.rateLimit(repositories.tenant(key)));
    }

    /** Set ({@code > 0}) or clear ({@code 0}) the tenant's request rate ceiling in permits per minute. */
    @PutMapping("/api/rate-limit")
    public void setRateLimit(@RequestHeader(value = Repositories.KEY, required = false) String key,
                             @RequestBody RateLimitRequest request, HttpServletResponse response) throws IOException {
        if (!rateLimiting) {
            respondRateLimitNotInstalled(response);
            return;
        }
        long permitsPerMinute = request == null ? 0L : request.permitsPerMinute();
        authorization.setRateLimit(repositories.tenant(key), permitsPerMinute);
        audit(key, "rate-limit.set", Long.toString(permitsPerMinute));
        response.setStatus(200);
    }

    /** The tenant's named roles (name to comma-separated tokens): built-in read-only/deploy/admin plus custom ones. */
    @GetMapping("/api/roles")
    @ResponseBody
    public Map<String, String> roles(@RequestHeader(value = Repositories.KEY, required = false) String key) throws IOException {
        return authorization.roles(repositories.tenant(key));
    }

    /** Add or replace a custom role by name from comma-separated tokens. */
    @PutMapping("/api/roles/{name}")
    public void setRole(@PathVariable("name") String name,
                        @RequestHeader(value = Repositories.KEY, required = false) String key,
                        @RequestBody RoleRequest request,
                        HttpServletResponse response) throws IOException {
        authorization.setRole(repositories.tenant(key), name, request.tokens());
        audit(key, AuditActions.ROLE_SET, name);
        response.setStatus(200);
    }

    @DeleteMapping("/api/roles/{name}")
    public void removeRole(@PathVariable("name") String name,
                           @RequestHeader(value = Repositories.KEY, required = false) String key,
                           HttpServletResponse response) throws IOException {
        authorization.removeRole(repositories.tenant(key), name);
        audit(key, AuditActions.ROLE_REMOVE, name);
        response.setStatus(200);
    }

    /** The tenant's audit trail, newest first, optionally bounded by ISO-8601 {@code from}/{@code to} instants and a
     *  single {@code action}, and paged so a request serves a bounded slice rather than the whole (unrotated) trail:
     *  by cursor ({@code after}, the {@code Jenesis-Next-Cursor} header of the previous answer, absent on the last page)
     *  or by {@code offset}/{@code limit} (default 0/500, limit clamped to 1000, offset to the trail's reach). The
     *  CSV export below streams the whole trail for off-system retention. */
    @GetMapping("/api/audit")
    @ResponseBody
    public List<AuditView> auditTrail(@RequestHeader(value = Repositories.KEY, required = false) String key,
                                      @RequestParam(name = "from", required = false) String from,
                                      @RequestParam(name = "to", required = false) String to,
                                      @RequestParam(name = "action", required = false) String action,
                                      @RequestParam(name = "offset", defaultValue = "0") int offset,
                                      @RequestParam(name = "after", required = false) String after,
                                      @RequestParam(name = "limit", defaultValue = "500") int limit,
                                      HttpServletResponse response) throws IOException {
        if (audit == AuditTrail.none()) {
            respondAuditNotInstalled(response);
            return null;
        }
        int size = Math.clamp(limit, 1, 1000);
        AuditTrail.Page page = after != null && !after.isBlank() || offset <= 0
                ? audit.query(repositories.tenant(key), instant(from), instant(to), action, after, size)
                : audit.query(repositories.tenant(key), instant(from), instant(to), action, offset, size);
        if (page.next() != null) {
            response.setHeader("Jenesis-Next-Cursor", page.next());
        }
        List<AuditView> views = new ArrayList<>();
        for (AuditTrail.Event event : page.events()) {
            views.add(new AuditView(event.at().toString(), event.actor(), event.action(), event.target()));
        }
        return views;
    }

    /** The same audit trail as a CSV download for off-system retention, streamed a row at a time straight to the
     *  response through the audit SPI's {@code stream} seam - neither a whole-trail StringBuilder (three full copies
     *  Spring would re-copy to a String then bytes) nor the SPI's materialised event list ever lands in heap, so a very
     *  large trail exports within a flat memory envelope (the store-backed trail holds only one day's events at a
     *  time). */
    @GetMapping(value = "/api/audit.csv", produces = "text/csv;charset=UTF-8")
    public void auditCsv(@RequestHeader(value = Repositories.KEY, required = false) String key,
                         @RequestParam(name = "from", required = false) String from,
                         @RequestParam(name = "to", required = false) String to,
                         @RequestParam(name = "action", required = false) String action,
                         HttpServletResponse response) throws IOException {
        if (audit == AuditTrail.none()) {
            respondAuditNotInstalled(response);
            return;
        }
        response.setContentType("text/csv;charset=UTF-8");
        Writer out = response.getWriter();
        out.write("at,actor,action,target\n");
        audit.stream(repositories.tenant(key), instant(from), instant(to), action, event ->
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

    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static String hashId(String id) {
        if (id == null || !HASH.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid credential id");
        }
        return id;
    }

    /** A blank value is no bound; otherwise an absolute ISO-8601 instant. */
    private static Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value.trim());
    }

    /** Quote a CSV field when it carries a comma, quote or newline, and prefix a leading {@code = + - @} (or tab or
     *  carriage return) with an apostrophe so a spreadsheet does not evaluate it as a formula. */
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
