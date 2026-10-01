package build.jenesis.repository.management.web;

import module java.base;
import build.jenesis.repository.server.kernel.PinnedSettings;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.posture.Configuration;
import build.jenesis.repository.posture.PostureReport;
import build.jenesis.repository.posture.Scope;
import build.jenesis.repository.posture.SecurityAdvisory;
import build.jenesis.repository.posture.Severity;
import build.jenesis.repository.settings.SettingsDocuments;
import build.jenesis.repository.settings.TenantPosture;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The security posture: {@code GET /api/admin/posture} returns every advisory a discovered
 * {@link build.jenesis.repository.posture.SafetyAdvisor} raises against the effective configuration - why the setting
 * is unsafe, the {@code jenrepo.*} key and value that fix it, a docs link - critical first. The superadmin-gated peer
 * of the key-gated {@code /api/posture}, rendered by the console's Security-posture page. Under {@code /api/admin/}: a
 * {@code manage:read} right and the operator tenant. Read-only, and it names the risk, never a secret
 * ({@link SecurityAdvisory}), so a surface listing weaknesses cannot leak one. Without this module there is no such
 * endpoint.
 *
 * <h2>The chain the server runs on</h2>
 * The configuration resolves through {@link PinnedSettings#effectiveProperty} - an operator's pin (variable,
 * {@code -D}, command line, external file) over the runtime {@link Settings} over the {@link Environment} - the chain
 * the running server and the console resolve through, so an advisory reflects the value in force. Stored over
 * environment without the pin would fail open: with {@code jenrepo.auth} pinned {@code false} and {@code true} stored,
 * no {@code jenrepo.auth.open} row would show while every request ran unauthenticated. {@link SpiCatalogController}
 * reads the same chain.
 *
 * <h2>One named tenant, never all</h2>
 * {@code ?tenant=<name>} collects a tenant's own advisories, as the console's selected tenant does; all tenants would
 * be the unbounded fan-out clause 12 forbids, so the parameter takes one name and the read costs the same whatever the
 * tenant count, while an omitted one is the deployment-wide view. Two scoping layers apply:
 * <ol>
 *   <li>The report is collected over one tenant's effective chain
 *       ({@link PinnedSettings#effective(Settings, Environment, String)}), the tenant reaching advisors through the
 *       reserved {@link TenantPosture#scoped} key, so an ambient {@code JENREPO_POSTURE_TENANT} re-attributes neither
 *       read.</li>
 *   <li>Only the rows {@link PostureReport#forTenant} selects for that tenant and the {@link Scope#DEPLOYMENT} rows are
 *       emitted, and the counts are over those rows, so another tenant's row appears nowhere, not even as a
 *       number.</li>
 * </ol>
 * These are the two calls the console's {@code ScopedPosture} makes; the type itself lives on the console's module
 * path, so they are repeated rather than required.
 *
 * <p>The name is validated as a store-safe tenant segment, a malformed one refused with a {@code 400} naming it. A
 * well-formed name that overrides nothing is answered with the deployment's chain, which is what its gate resolves.
 */
@RestController
public class PostureAdminController {

    private final Settings settings;
    private final Environment environment;
    private final PinnedSettings pins;

    public PostureAdminController(Settings settings, Environment environment, PinnedSettings pins) {
        this.settings = settings;
        this.environment = environment;
        this.pins = pins;
    }

    /** The posture report: the advisory count and per-severity tallies, then the severity-sorted advisories.
     *  {@code version} lets a client detect a shape change, and {@code tenant} echoes the tenant collected for, blank
     *  for the deployment-wide read. */
    @GetMapping("/api/admin/posture")
    @ResponseBody
    public PostureView posture(@RequestParam(name = "tenant", required = false) String tenant) {
        String named = tenant == null ? "" : tenant.strip();
        if (!named.isEmpty() && !SettingsDocuments.validTenant(named)) {
            throw new IllegalArgumentException("'" + named + "' is not a tenant name. Ask for one named tenant's "
                    + "posture (?tenant=<name>) or omit the parameter for the deployment-wide report; there is no "
                    + "all-tenants form - enumerating every tenant's effective gate is the unbounded fan-out a "
                    + "SafetyAdvisor is forbidden.");
        }
        // One chain, as the running server resolves its dials: an operator's pin, else the stored jenrepo.* value, else
        // the environment, which alone answers a key with no stored form. A named tenant's document comes first in the
        // middle of the chain, exactly one tenant's.
        Configuration config = Configuration.of(
                pins.effectiveProperty(settings, environment, named.isEmpty() ? null : named));
        // The reserved tenant key answers in both directions - the named tenant, or nothing - so it never falls through
        // to the environment.
        PostureReport report = PostureReport.discover(TenantPosture.scoped(named.isEmpty() ? null : named, config));
        // The named tenant's rows (none when unnamed) and the deployment-wide ones; tallies are over exactly these.
        List<SecurityAdvisory> shown = new ArrayList<>();
        if (!named.isEmpty()) {
            shown.addAll(report.forTenant(named));
        }
        shown.addAll(report.scoped(Scope.DEPLOYMENT));
        List<AdvisoryView> rows = new ArrayList<>();
        for (SecurityAdvisory advisory : shown) {
            rows.add(new AdvisoryView(advisory.id(), advisory.severity().name(), advisory.scope().name(),
                    advisory.tenant(), advisory.title(), advisory.why(), advisory.fix(),
                    advisory.settingKey(), advisory.settingValue(), advisory.docs()));
        }
        return new PostureView(1, named, shown.size(), count(shown, Severity.CRITICAL), count(shown, Severity.WARN),
                count(shown, Severity.INFO), rows);
    }

    /** How many emitted advisories are at {@code severity}, counted over what this document carries. */
    private static long count(List<SecurityAdvisory> shown, Severity severity) {
        return shown.stream().filter(advisory -> advisory.severity() == severity).count();
    }

    /** A malformed {@code ?tenant=} is refused, naming what was asked and what the endpoint offers. */
    @ExceptionHandler(IllegalArgumentException.class)
    public void badRequest(IllegalArgumentException refused, HttpServletResponse response) throws IOException {
        response.setStatus(400);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(refused.getMessage());
    }

    /** The posture report: the tenant collected for (blank deployment-wide), the advisory count, the per-severity
     *  tallies and the severity-sorted advisories. {@code version} lets a client detect a shape change. */
    public record PostureView(int version, String tenant, int count, long critical, long warn, long info,
                              List<AdvisoryView> advisories) {

        public PostureView {
            advisories = List.copyOf(advisories);
        }
    }

    /** One advisory: its id, severity, scope (and the tenant a tenant-scoped one concerns), title, why it is unsafe,
     *  the fix, the setting key and value to change, and a docs link. Names the risk, never a secret. */
    public record AdvisoryView(String id, String severity, String scope, String tenant, String title, String why,
                               String fix, String settingKey, String settingValue, String docs) {
    }
}
