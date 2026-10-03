package build.jenesis.repository.console.api;

import module java.base;

import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.RepositoryRequests;
import build.jenesis.repository.ui.store.RepositoryBrowse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import build.jenesis.repository.store.ServableNames;

/**
 * The <em>origin</em> audit-export API: the {@code origin} acquisition rows of a published or fallback-fetched path in
 * a tenant's repository - where this deployment's bytes for the coordinate version came from (a hand upload, or which
 * fallback fetched them, when, stored or passed through). The key-header twin of the console's origin panel
 * ({@code /repositories/{repo}/artifact/origin}), so a headless audit job can pull the trail without a session.
 *
 * <p>Both share {@link RepositoryBrowse#originOf}, which unions the coordinate's format-coordinate document (an
 * upload's {@code local-upload} row) and its path-derived document (a fallback's {@code fallback} row), so an uploaded,
 * a fetched and a locally shadowed artifact all read back their full history - including a no-store fallback's durable
 * row. Only the two small sections are read.
 */
@RestController
public class OriginController {

    private final Repositories repositories;
    private final RepositoryRouting routing;

    public OriginController(Repositories repositories, RepositoryRouting routing) {
        this.repositories = repositories;
        this.routing = routing;
    }

    @GetMapping("/api/origin")
    @ResponseBody
    public List<RepositoryBrowse.OriginRow> origin(@RequestParam("repo") String repo,
                                                   @RequestParam(value = "path", defaultValue = "") String path,
                                                   HttpServletRequest request,
                                                   HttpServletResponse response) throws IOException {
        String tenant = RepositoryRequests.access(routing, repo, request, response);
        if (tenant == null) {
            return null;
        }
        // Confined to the tenant's repository and traversal-guarded as the console panel is
        // (ServableNames.safePrefix); the client's path is mapped to the format's stored layout.
        return RepositoryBrowse.originOf(repositories.store(tenant, repo),
                ServableNames.safePrefix(repositories.formatPath(tenant, repo, ServableNames.safePrefix(path))));
    }
}
