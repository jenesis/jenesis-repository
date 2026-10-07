package build.jenesis.repository.compliance.web;

import module java.base;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.PinnedSettings;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.RepositoryRequests;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.gate.RetroLicensePlanner;
import build.jenesis.repository.store.ArtifactStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The retroactive licence enforcement dry run: what enabling enforcement would newly hold in a repository, computed by
 * the discovered {@link RetroLicensePlanner}, so the preview and the sweep agree. The plan assesses every release, so
 * {@code refresh=true} starts it off the request and every read answers its state - {@link LicenseBlastRadius}, the
 * one implementation the console's enforcement preview runs too. Gated {@code manage:read}, the tenant the managing
 * key's; without a planner the answer is {@code 501}, after the authorization check.
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
    public LicenseBlastRadius.View plan(@RequestParam("repo") String repo,
                                       @RequestParam(value = "unknown", defaultValue = "false") boolean unknown,
                                       @RequestParam(value = "refresh", defaultValue = "false") boolean refresh,
                                       HttpServletRequest request,
                                       HttpServletResponse response) throws IOException {
        String tenant = RepositoryRequests.access(routing, repo, request, response);
        if (tenant == null) {
            return null;
        }
        if (!blastRadius.installed()) {
            response.setStatus(501);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("license policy is not installed on this deployment");
            return null;
        }
        ArtifactStore store = repositories.store(tenant, repo);
        if (refresh) {
            // Every release is assessed, so the plan runs off the request; the dials resolved as the running gate
            // resolves them, pins included.
            boolean started = blastRadius.start(store, pins.effective(settings, environment), unknown,
                    UnaryOperator.identity());
            response.setHeader(REFRESH_HEADER, started ? "started" : "running");
        }
        return blastRadius.read(store, unknown);
    }

    /** Says, on a refreshed read, whether this request started the plan or found one already running. */
    public static final String REFRESH_HEADER = "Jenesis-Refresh";

    /** The planner, resolved once, since what is installed is fixed for the JVM. */
    private final LicenseBlastRadius blastRadius = new LicenseBlastRadius(RetroLicensePlanner.installed());
}
