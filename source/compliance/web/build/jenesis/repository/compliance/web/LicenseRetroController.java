package build.jenesis.repository.compliance.web;

import module java.base;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.PinnedSettings;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.RepositoryRequests;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.gate.RetroLicensePlanner;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The retroactive licence enforcement dry run: what enabling enforcement would newly hold in a repository, computed by
 * the discovered {@link RetroLicensePlanner}, so the preview and the sweep agree. Gated {@code manage:read}, the tenant
 * the managing key's; without a planner the answer is {@code 501}, after the authorization check.
 */
@RestController
public class LicenseRetroController {

    private final Repositories repositories;
    private final RepositoryRouting routing;
    private final Settings settings;
    private final Environment environment;

    private final PinnedSettings pins;

    public LicenseRetroController(Repositories repositories, RepositoryRouting routing, Settings settings,
                                  Environment environment, PinnedSettings pins) {
        this.repositories = repositories;
        this.routing = routing;
        this.settings = settings;
        this.environment = environment;
        this.pins = pins;
    }

    @GetMapping("/api/licenses/retro/plan")
    @ResponseBody
    public PlanView plan(@RequestParam("repo") String repo,
                         @RequestParam(value = "unknown", defaultValue = "false") boolean unknown,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        String tenant = RepositoryRequests.access(routing, repo, request, response);
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
        // The dials resolved as the running gate resolves them, pins included.
        RetroLicensePlanner.Plan plan = planner.get().plan(pins.effective(settings, environment),
                repositories.store(tenant, repo), unknown);
        List<HeldView> held = new ArrayList<>();
        for (RetroLicensePlanner.Held entry : plan.held()) {
            held.add(new HeldView(entry.ecosystem(), entry.coordinate(), entry.version(), entry.reasons()));
        }
        return new PlanView(unknown ? "denied+unknown" : "denied", plan.count(), held);
    }

    /** The planner, resolved once, since what is installed is fixed for the JVM. */
    private final Optional<RetroLicensePlanner> planner = RetroLicensePlanner.installed();

    /** The dry-run plan for one repository: the mode previewed, how many releases a fresh enabling pass would newly
     *  hold, and the per-coordinate reasons behind them. */
    public record PlanView(String mode, int count, List<HeldView> held) {
    }

    /** One release the sweep would hold, with the human-readable reasons. */
    public record HeldView(String ecosystem, String coordinate, String version, List<String> reasons) {
    }
}
