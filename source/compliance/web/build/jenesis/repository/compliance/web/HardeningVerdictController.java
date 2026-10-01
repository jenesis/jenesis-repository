package build.jenesis.repository.compliance.web;

import module java.base;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.RepositoryRequests;
import build.jenesis.repository.gateway.HardeningVerdicts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read-only hardening API: what the hardened proxy leg recorded for a repository - a coordinate's digest-pinned
 * {@code verdict} with its {@code screenedAt}, the recent typed {@code refusals}, the gateway-wide {@code drift} alarm -
 * assembled by {@link HardeningVerdicts} from durable state, never re-screening a byte. Gated {@code manage:read}; an
 * unsafe name is a {@code 400}.
 */
@RestController
public class HardeningVerdictController {

    /** How many recent hardened refusals the panel surfaces - a bounded page of the durable ledger, never a full scan. */
    private static final int REFUSAL_LIMIT = 25;

    private final Repositories repositories;
    private final RepositoryRouting routing;

    public HardeningVerdictController(Repositories repositories, RepositoryRouting routing) {
        this.repositories = repositories;
        this.routing = routing;
    }

    @GetMapping("/api/hardening/verdict")
    @ResponseBody
    public HardeningView verdict(@RequestParam("repo") String repo,
                                 @RequestParam(value = "path", required = false) String path,
                                 HttpServletRequest request,
                                 HttpServletResponse response) throws IOException {
        String tenant = RepositoryRequests.access(routing, repo, request, response);
        if (tenant == null) {
            return null;
        }
        boolean hardened = repositories.hardened(tenant, repo);
        HardeningVerdicts verdicts = HardeningVerdicts.over(repositories.store(tenant, repo));
        HardeningVerdicts.View view = (path == null || path.isBlank())
                ? new HardeningVerdicts.View(null, null, verdicts.refusals(REFUSAL_LIMIT),
                        new HardeningVerdicts.Drift(build.jenesis.repository.gateway.HardenedScreen.driftEvents()))
                : verdicts.view(repositories.formatPath(tenant, repo, path), REFUSAL_LIMIT);
        String viewed = view.path() == null ? null : repositories.servedPath(tenant, repo, view.path());
        return new HardeningView(repo, hardened, viewed, view.screened(), view.screenedAt(), view.verdict(),
                view.refusals(), view.drift());
    }

    /** A traversal-unsafe repository or tenant name is a {@code 400}. */
    @ExceptionHandler(IllegalArgumentException.class)
    public void badRequest(HttpServletResponse response) {
        response.setStatus(400);
    }

    /** The hardening read: whether the repository hardens, the coordinate, when it was screened, its verdict
     *  ({@code null} if never), the recent refusals and the drift alarm. */
    public record HardeningView(String repo, boolean hardened, String path, boolean screened, String screenedAt,
                                HardeningVerdicts.RecordedVerdict verdict, List<HardeningVerdicts.Refusal> refusals,
                                HardeningVerdicts.Drift drift) {
    }
}
