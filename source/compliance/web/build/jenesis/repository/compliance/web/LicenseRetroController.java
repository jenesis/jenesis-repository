package build.jenesis.repository.compliance.web;

import module java.base;
import build.jenesis.repository.server.kernel.PinnedSettings;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.gate.RetroLicensePlanner;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The retroactive-license-enforcement dry-run surface: a read-only preview of what turning enforcement on would hold in
 * a repository under the current license policy, so an operator reviews the blast radius before flipping the switch.
 * Peeled into the compliance {@code web} adapter beside the quarantine review it feeds, and gated {@code manage:read}
 * (a GET under {@code /api/}) by the security chain before the request is reached; the tenant is the one carried by the
 * managing key, so two tenants never preview each other's space. The plan itself is computed by the discovered
 * {@link RetroLicensePlanner} the {@code compliance/licenses} module provides - with no license-policy module installed
 * the endpoint answers {@code 501}, after the auth check so {@code 401}/{@code 403} still precede - and lists exactly
 * what a fresh enabling pass would newly hold, so the preview and the sweep never disagree.
 */
@RestController
public class LicenseRetroController {

    private final Repositories repositories;
    private final Settings settings;
    private final Environment environment;

    private final PinnedSettings pins;

    public LicenseRetroController(Repositories repositories, Settings settings, Environment environment,
                                  PinnedSettings pins) {
        this.repositories = repositories;
        this.settings = settings;
        this.environment = environment;
        this.pins = pins;
    }

    @GetMapping("/api/licenses/retro/plan")
    @ResponseBody
    public PlanView plan(@RequestParam("repo") String repo,
                         @RequestParam(value = "unknown", defaultValue = "false") boolean unknown,
                         @RequestHeader(value = Repositories.KEY, required = false) String key,
                         HttpServletResponse response) throws IOException {
        String tenant = access(repo, key, response);
        if (tenant == null) {
            return null;
        }
        Optional<RetroLicensePlanner> planner = this.planner;
        if (planner.isEmpty()) {
            response.setStatus(501);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("license policy is not installed on this deployment");
            return null;
        }
        // The planner reads the licence policy dials through the same chain the running gate resolves them through -
        // an operator's pin over the stored value over the deployment environment - so a plan is a plan against
        // the policy in force, not against a stored value a pin above it makes inert.
        RetroLicensePlanner.Plan plan = planner.get().plan(pins.effective(settings, environment),
                repositories.store(tenant, repo), unknown);
        List<HeldView> held = new ArrayList<>();
        for (RetroLicensePlanner.Held entry : plan.held()) {
            held.add(new HeldView(entry.ecosystem(), entry.coordinate(), entry.version(), entry.reasons()));
        }
        return new PlanView(unknown ? "denied+unknown" : "denied", plan.count(), held);
    }

    /**
     * Validates the named repository and resolves the request's tenant from the {@code Jenesis-Repository-Key} header,
     * answering {@code 400} for a traversal-unsafe repository or tenant name and {@code null} so the caller returns at
     * once. Rights are enforced by Spring Security before the request reaches the controller, so this makes no
     * authorization decision.
     */
    /** The planner, resolved once: whether the licence-policy module is installed cannot change within a JVM,
     *  and this route answered that question with a module-graph walk on every call. */
    private final Optional<RetroLicensePlanner> planner = RetroLicensePlanner.installed();

    private String access(String repo, String key, HttpServletResponse response) {
        if (!Repositories.valid(repo)) {
            response.setStatus(400);
            return null;
        }
        String tenant = repositories.tenant(key);
        if (!Repositories.valid(tenant)) {
            response.setStatus(400);
            return null;
        }
        return tenant;
    }

    /** The dry-run plan for one repository: the mode previewed, how many releases a fresh enabling pass would newly
     *  hold, and the per-coordinate reasons behind them. */
    public record PlanView(String mode, int count, List<HeldView> held) {
    }

    /** One release the sweep would hold, with the human-readable reasons. */
    public record HeldView(String ecosystem, String coordinate, String version, List<String> reasons) {
    }
}
