package build.jenesis.repository.management.web;

import module java.base;
import build.jenesis.repository.observation.ObservabilityReport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The metrics overview: {@code GET /api/admin/observability} returns the collected {@link ObservabilityReport} - the
 * overall verdict, then the health checks, metrics and task statuses with their {@code jenrepo.<feature>.<signal>}
 * names and descriptions - in one document, the JSON the console's overview renders; a metric with a {@code limit}
 * carries its {@code usage()}. Under {@code /api/admin/}, so {@code RepositoryAuthorizationManager} scopes it
 * deployment-global: a {@code manage:read} right and the operator tenant. Read-only; an absent source contributes
 * nothing. Without this module there is no such endpoint, while the operator-gated {@code /actuator/observability}
 * stands.
 */
@RestController
public class ObservabilityAdminController {

    private final Supplier<ObservabilityReport> reports;

    /**
     * @param reports the collected report: {@link ObservabilityReport#discover} in the server, a fixture in a test
     */
    public ObservabilityAdminController(Supplier<ObservabilityReport> reports) {
        this.reports = Objects.requireNonNull(reports, "reports");
    }

    /** The collected report as one document; {@code version} lets a client detect a shape change. */
    @GetMapping("/api/admin/observability")
    @ResponseBody
    public ObservabilityReport.View observability() {
        return reports.get().view();
    }
}
