package build.jenesis.repository.management.web;

import module java.base;
import build.jenesis.repository.observation.ObservabilityReport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The metrics-overview admin read: {@code GET /api/admin/observability} returns the whole collected {@link
 * ObservabilityReport} this deployment carries - the overall verdict, then the self-describing health checks, metrics
 * and background-task statuses, each with its stable {@code jenreg.<feature>.<signal>} name and the human-readable
 * description from its registration - so an operator (or a headless agent) reads every metric, health state and task
 * status in one document rather than scraping {@code /actuator/health}, {@code /actuator/metrics} and the task
 * surfaces. It is the JSON the console's metrics-overview page renders as a plain, no-graphs overview; a metric that
 * declares a {@code limit} carries its {@code usage()} fraction so the page shows used-vs-available without
 * pre-computing a percentage. Under {@code /api/admin/}, so the {@code RepositoryAuthorizationManager} scopes it
 * deployment-global: a {@code manage:read} right <em>and</em> the operator tenant, exactly like the SPI catalogue and
 * the storage-manifest admin reads. Read-only, and it degrades gracefully - a disabled or absent source contributes
 * nothing, so an empty report is a friendly empty document, never an error. Contributed through the {@code
 * ServerModuleProvider} seam; with this module absent the server carries no {@code /api/admin/observability} endpoint
 * (the operator-gated {@code /actuator/observability} Actuator read still stands independently).
 */
@RestController
public class ObservabilityAdminController {

    private final Supplier<ObservabilityReport> reports;

    /** @param reports the collected report to render - {@link ObservabilityReport#discover} in the running server, a
     *  fixture in a test. */
    public ObservabilityAdminController(Supplier<ObservabilityReport> reports) {
        this.reports = Objects.requireNonNull(reports, "reports");
    }

    /** The full collected report as one document: the overall verdict, then the self-describing health checks, metrics
     *  and task statuses (name-sorted by the report), each carrying its name and registration description.
     *  {@code version} lets a client detect a future shape change. */
    @GetMapping("/api/admin/observability")
    @ResponseBody
    public ObservabilityReport.View observability() {
        return reports.get().view();
    }
}
