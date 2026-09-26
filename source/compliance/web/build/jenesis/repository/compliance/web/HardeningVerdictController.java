package build.jenesis.repository.compliance.web;

import module java.base;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.gateway.HardeningVerdicts;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read-only hardening console API: what the now-invisible hardened proxy leg has durably decided for
 * a hardened repository, so an operator can see the full-body screening it enforces. For a coordinate it surfaces the
 * digest-pinned {@code verdict} record, the repository's recent typed {@code refusals} (oversize/stalled/drift/
 * unparseable/inspector-error) and the gateway-wide {@code drift} alarm - all assembled by {@link HardeningVerdicts}
 * from the durable metadata document and {@code QuarantineLog}, <b>never re-screening or re-fetching a byte</b> (§10
 * reads render only durable state; §7 the reader pays for nothing a screen already did). The recorded {@code screenedAt}
 * instant rides along so a caller sees how stale the rendered verdict is - a caller lacking the write role sees no
 * refresh control yet still sees the staleness.
 *
 * <p>Contributed through the same {@code ServerModuleProvider} seam as the sibling {@link QuarantineController}: with
 * this module off the path the endpoint does not exist and the console hides the panel. Every {@code /api/} GET is a
 * deployment-management read the security chain already gates {@code manage:read} before the request is reached, so this
 * controller makes no authorization decision of its own; a traversal-unsafe repository or tenant name is a {@code 400}.
 */
@RestController
public class HardeningVerdictController {

    /** How many recent hardened refusals the panel surfaces - a bounded page of the durable ledger, never a full scan. */
    private static final int REFUSAL_LIMIT = 25;

    private final Repositories repositories;

    public HardeningVerdictController(Repositories repositories) {
        this.repositories = repositories;
    }

    @GetMapping("/api/hardening/verdict")
    @ResponseBody
    public HardeningView verdict(@RequestParam("repo") String repo,
                                 @RequestParam(value = "path", required = false) String path,
                                 @RequestHeader(value = Repositories.KEY, required = false) String key,
                                 HttpServletResponse response) throws IOException {
        String tenant = access(repo, key, response);
        if (tenant == null) {
            return null;
        }
        boolean hardened = repositories.hardened(repo);
        // Reuse the durable read paths only: the consolidated metadata document (the recorded verdict) and the
        // QuarantineLog (the recorded refusals), plus the gateway-wide drift counter. No screen, no fetch.
        HardeningVerdicts verdicts = HardeningVerdicts.over(repositories.store(tenant, repo));
        HardeningVerdicts.View view = (path == null || path.isBlank())
                ? new HardeningVerdicts.View(null, null, verdicts.refusals(REFUSAL_LIMIT),
                        new HardeningVerdicts.Drift(build.jenesis.repository.gateway.HardenedScreen.driftEvents()))
                : verdicts.view(repositories.formatPath(tenant, repo, path), REFUSAL_LIMIT);
        String viewed = view.path() == null ? null : repositories.servedPath(tenant, repo, view.path());
        return new HardeningView(repo, hardened, viewed, view.screened(), view.screenedAt(), view.verdict(),
                view.refusals(), view.drift());
    }

    /** A traversal-unsafe repository or tenant name is a {@code 400}, mirroring the sibling controllers so a rejected
     *  name never surfaces as a {@code 500}. */
    @ExceptionHandler(IllegalArgumentException.class)
    public void badRequest(HttpServletResponse response) {
        response.setStatus(400);
    }

    /**
     * Validates the named repository and resolves the request's tenant from the {@code Jenesis-Repository-Key} header,
     * answering {@code 400} for a traversal-unsafe repository or tenant name and {@code null} so the caller returns at
     * once. Rights are enforced by the security chain before the controller is reached (every {@code /api/} GET needs
     * {@code manage:read}), so this makes no authorization decision - the same guard {@link QuarantineController} carries.
     */
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

    /** The hardening read as the console renders it: the repository and whether it is a hardened proxy (drives the
     *  badge/panel gate), the coordinate read, whether it has ever been screened and when (the staleness line), the
     *  digest-pinned verdict (null when never screened), the recent typed refusals and the drift alarm. */
    public record HardeningView(String repo, boolean hardened, String path, boolean screened, String screenedAt,
                                HardeningVerdicts.RecordedVerdict verdict, List<HardeningVerdicts.Refusal> refusals,
                                HardeningVerdicts.Drift drift) {
    }
}
