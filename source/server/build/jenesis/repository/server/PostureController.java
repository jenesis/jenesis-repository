package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.posture.Configuration;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.posture.PostureReport;
import build.jenesis.repository.posture.SecurityAdvisory;
import tools.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The security-posture read - {@code GET /api/posture}, the console / CLI / headless-agent read of the
 * deployment's configuration-warning advisories: every potentially-unsafe setting a discovered {@link
 * build.jenesis.repository.posture.SafetyAdvisor} raises against the effective configuration, collected once through
 * {@link PostureReport#discover} and returned severity-sorted (critical first). Each row names <em>why</em> a setting is
 * unsafe and the exact {@code jenreg.*} key/value that fixes it - it never repeats a read secret value, so this
 * surface (which enumerates the deployment's weaknesses) cannot itself leak one.
 *
 * <p>Registered as an explicit {@code @Bean} by {@link RepositoryAutoConfiguration}, reading the deployment
 * configuration off the Spring {@link Environment} (the same lookup {@code Features} installs). Read like every other
 * {@code /api} surface - key-auth'd ({@code repository:read}) by {@link RepositorySecurityAutoConfiguration}, read-only,
 * never an anonymous backdoor; a clean deployment returns an empty list. The downstream edition mirrors this
 * independently as a superadmin-gated {@code /api/admin/posture}.
 */
@RestController
public final class PostureController {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Environment environment;

    public PostureController(Environment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @GetMapping("/api/posture")
    public void posture(HttpServletRequest request, HttpServletResponse response) throws IOException {
        PostureReport report = PostureReport.discover(Configuration.of(environment::getProperty));
        // Deployment-wide rows for everyone; a tenant's rows only for a key that belongs to that tenant. Rendering
        // report.advisories() handed every TENANT-scoped advisory to any repository:read caller - the §6 leak, on
        // the surface whose whole job is to enumerate the deployment's weaknesses. A caller with no key (auth off,
        // or an anonymous read) resolves to no tenant and sees the deployment rows alone, which is the fail-closed
        // direction. This is the same composition the downstream console's ScopedPosture performs; the primitives
        // it uses live here, in the free report, and were simply not being called.
        List<SecurityAdvisory> visible = report.visibleTo(Authorization.tenantOf(PresentedKey.from(request)));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SecurityAdvisory advisory : visible) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", advisory.id());
            row.put("severity", advisory.severity().name());
            row.put("scope", advisory.scope().name());
            row.put("tenant", advisory.tenant());
            row.put("title", advisory.title());
            row.put("why", advisory.why());
            row.put("fix", advisory.fix());
            row.put("settingKey", advisory.settingKey());
            row.put("settingValue", advisory.settingValue());
            row.put("docs", advisory.docs());
            rows.add(row);
        }
        // Counted over what is rendered, not over the whole report: a total that included rows the caller may not
        // see would report the existence of another tenant's advisories, which is the same leak one field over.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", visible.size());
        body.put("critical", severity(visible, build.jenesis.repository.posture.Severity.CRITICAL));
        body.put("warn", severity(visible, build.jenesis.repository.posture.Severity.WARN));
        body.put("info", severity(visible, build.jenesis.repository.posture.Severity.INFO));
        body.put("advisories", rows);
        response.setHeader("Content-Type", "application/json");
        response.setStatus(200);
        byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = response.getOutputStream()) {
            out.write(bytes);
        }
    }

    /** How many of the rendered rows carry {@code severity} - the counts must describe the body, not the report. */
    private static long severity(List<SecurityAdvisory> visible, build.jenesis.repository.posture.Severity severity) {
        return visible.stream().filter(advisory -> advisory.severity() == severity).count();
    }
}
