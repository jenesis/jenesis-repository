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
 * The security-posture admin read: {@code GET /api/admin/posture} returns every potentially-unsafe
 * configuration a discovered {@link build.jenesis.repository.posture.SafetyAdvisor} raises against this deployment's
 * effective configuration - each row naming <em>why</em> the setting is unsafe, the exact {@code jenreg.*} key/value
 * that fixes it and a docs link, severity-sorted (critical first). It is the superadmin-gated peer of the
 * free key-gated {@code /api/posture}, the JSON the console's Security-posture page renders and a headless agent reads.
 *
 * <p>Under {@code /api/admin/}, so the {@code RepositoryAuthorizationManager} scopes it deployment-global: a
 * {@code manage:read} right <em>and</em> the operator tenant, exactly like the SPI-catalogue and metrics-overview
 * admin reads. Read-only - observing posture never mutates it - and it <b>names the risk, never a secret value</b> (a
 * {@link SecurityAdvisory} carries the condition and the fix, never a read credential), so this surface, which
 * enumerates the deployment's weaknesses, cannot itself leak one. A clean deployment returns an empty list (the
 * healthy state), so it degrades gracefully. Contributed through the {@code ServerModuleProvider} seam; with this
 * module absent the server carries no {@code /api/admin/posture} endpoint (the {@code /api/posture} still stands
 * independently).
 *
 * <h2>The chain the server runs on, not the one the store holds</h2>
 * The effective configuration is resolved through {@link PinnedSettings#effectiveProperty} - an operator's <em>pin</em>
 * (an environment variable, a {@code -D}, the command line or an external config file) over the runtime
 * {@link Settings} over the deployment {@link Environment} - the same chain {@code LiveConfig} resolves the running
 * server's dials through and the console's Security-posture screen reads. So an advisory reflects a live setting change
 * rather than only the boot value, <em>and</em> it reflects the value that is actually in force.
 *
 * <p>This read used to resolve stored-over-environment and never above a pin, which on this surface fails open rather
 * than merely disagreeing: for a key an operator has pinned <b>and</b> the store also holds, the stored value is inert -
 * the server runs on the pin - so the report described the deployment as configured instead of as running. With
 * {@code jenreg.auth} pinned {@code false} and {@code true} left in the store, the report carried no
 * {@code jenreg.auth.open} row while every request, including the one reading the report, was being served with no
 * credential at all. A headless operator was told the deployment was safe when it was not, and the console - which read
 * the right chain - disagreed with it. {@link SpiCatalogController} carried the same shape and took the same retrofit.
 *
 * <h2>One <em>named</em> tenant, never all of them</h2>
 * The console's Security-posture screen was given the selected tenant's own advisories - conditions about a tenant's
 * admission policy, raised at {@link Scope#TENANT} against that tenant's effective chain. This read held no tenant, so
 * it dropped them entirely and a headless operator could not obtain what the console shows, although the API and the
 * console are documented as equal administration surfaces (&sect;13).
 *
 * <p>{@code ?tenant=<name>} closes that, and closes it the only way the {@code SafetyAdvisor} contract allows. The
 * refusal that made this endpoint tenant-less is <b>not</b> relaxed: enumerating every tenant's effective gate on one
 * request is the unbounded fan-out clause 12 forbids, and there is still no way to ask for "all tenants" - the
 * parameter takes exactly one name, the read costs the same whatever the deployment's tenant count, and an omitted
 * parameter is the deployment-wide view, never a sweep. What changes is only that an operator who <em>knows</em> which
 * tenant they are administering can name it, which is precisely what the console session does when it selects one.
 *
 * <p>Both scoping layers the console applies are applied here too, because a report is a fan-out over discovered
 * providers and an advisory names its own tenant:
 * <ol>
 *   <li>The report is collected over <em>one</em> tenant's effective chain (an operator's pin over that tenant's
 *       document over the deployment document over the environment, through
 *       {@link PinnedSettings#effective(Settings, Environment, String)}), and the tenant
 *       reaches the advisors through the reserved {@link TenantPosture#scoped} context key rather than the
 *       environment - so an ambient {@code JENREG_POSTURE_TENANT} can re-attribute neither a named read nor an
 *       unnamed one.</li>
 *   <li>The rows emitted are the ones {@link PostureReport#forTenant} selects for the named tenant plus the
 *       {@link Scope#DEPLOYMENT} ones - never every {@code TENANT}-scoped row the fan-out happened to return. A row
 *       naming another tenant appears in neither list <em>and in none of the counts</em>, which are tallied over what
 *       is emitted rather than over {@link PostureReport#count()}. Without a tenant the tenant half is empty, so an
 *       unnamed read is deployment-wide by construction rather than by an advisor's good behaviour.</li>
 * </ol>
 *
 * <p>The two selection calls are the store's own, not a second copy of the console's mechanism: the console's
 * {@code ScopedPosture} makes exactly this pair against a report that really carries two tenants' rows, which is the
 * only shape in which dropped scoping is observable, and its {@code ScopedPostureTest} is where that falsification
 * lives. {@code ScopedPosture} itself is a console view model on the console node's module path, and requiring it here
 * would drag the console's domain services onto a repository node (&sect;2), which is why this repeats two
 * calls rather than the type.
 *
 * <p>The name is validated as a store-safe tenant segment before it is used, and a malformed one is refused with a
 * {@code 400} naming it rather than silently answered as though no tenant had been asked for (&sect;9). A well-formed
 * name that has overridden nothing is <em>not</em> refused: its effective chain is the deployment's, which is exactly
 * what that tenant's gate would resolve, and {@code TenantPosture} is explicit that a deployment whose baseline is
 * unsafe shows the row in every tenant's view because every tenant's gate really is open. Answering it costs one
 * empty listing and no tenant-directory read, so the read stays independent of how many tenants exist.
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

    /** The whole posture report - the advisory count and per-severity tallies (what the console badge shows) then the
     *  severity-sorted advisories, each self-describing. {@code version} lets a client detect a future shape change,
     *  and {@code tenant} echoes the tenant the rows were collected for (blank for the deployment-wide read), so a
     *  client can tell the two documents apart and discover that the parameter exists. */
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
        // The effective configuration an advisor reads, resolved through the one chain the running server resolves its
        // dials through (PinnedSettings): an operator's PIN from above the store, else the stored
        // jenreg.* value, else the deployment Environment - and the Environment alone for a
        // non-jenreg.repository key (spring.profiles.active, jenreg.ui.admins), which has no stored form to pin. With
        // a tenant named, the tenant-overridable middle of that chain resolves against THAT tenant's document first -
        // exactly one tenant's, which is what keeps a tenant-scoped row attributable to the tenant it names.
        Configuration config = Configuration.of(
                pins.effectiveProperty(settings, environment, named.isEmpty() ? null : named));
        // The reserved tenant context key is answered by TenantPosture#scoped in both directions - the named tenant
        // for a scoped read, nothing at all for the deployment-wide one - so it never falls through to the
        // environment and an ambient JENREG_POSTURE_TENANT cannot make either read start attributing rows.
        PostureReport report = PostureReport.discover(TenantPosture.scoped(named.isEmpty() ? null : named, config));
        // What this document may carry: the named tenant's own rows (none when unnamed) and the deployment-wide ones.
        // Anything else - a row for a tenant that was not asked about - is emitted by neither leg, and the tallies are
        // computed over exactly these rows rather than over report.count(), so it cannot leak even as a number.
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

    /** How many of the emitted advisories are at {@code severity} - counted over what this document actually carries,
     *  so a row it does not emit is never counted into a number it does. */
    private static long count(List<SecurityAdvisory> shown, Severity severity) {
        return shown.stream().filter(advisory -> advisory.severity() == severity).count();
    }

    /** A malformed {@code ?tenant=} is refused rather than quietly answered as the deployment-wide report, and the
     *  refusal names what was asked for and what the endpoint does offer (&sect;9). */
    @ExceptionHandler(IllegalArgumentException.class)
    public void badRequest(IllegalArgumentException refused, HttpServletResponse response) throws IOException {
        response.setStatus(400);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(refused.getMessage());
    }

    /** The collected posture report: the tenant it was collected for (blank for the deployment-wide read), the total
     *  advisory count, the per-severity tallies, and the severity-sorted advisories. {@code version} lets a client
     *  detect a future shape change. */
    public record PostureView(int version, String tenant, int count, long critical, long warn, long info,
                              List<AdvisoryView> advisories) {

        public PostureView {
            advisories = List.copyOf(advisories);
        }
    }

    /** One self-describing advisory: its stable id, severity, scope (and the tenant a tenant-scoped one concerns), a
     *  short title, the plain why-this-is-unsafe, the suggested fix, the exact setting key/value to change, and a docs
     *  link. Names the risk, never a secret value. */
    public record AdvisoryView(String id, String severity, String scope, String tenant, String title, String why,
                               String fix, String settingKey, String settingValue, String docs) {
    }
}
