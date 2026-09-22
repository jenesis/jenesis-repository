package build.jenesis.repository.console.api;

import module java.base;

import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.RepositoryRequests;
import build.jenesis.repository.ui.store.RepositoryBrowse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The machine-readable <em>origin</em> (provenance-of-source) audit-export API (EPIC 25 §6.2): the {@code origin}
 * acquisition rows of a published or fallback-fetched artifact path over a tenant's named repository - where <em>this</em>
 * deployment's bytes for the coordinate version came from (a hand upload, or which fallback fetched them, when, stored or
 * passed through). It is the key-header-authenticated twin of the console's session-scoped origin panel
 * ({@code /repositories/{repo}/artifact/origin}), reachable by an operator tool or audit export the same anonymous /
 * key-header way the sibling {@code BrowseController} browse/search reads are - so the durable origin trail can be pulled
 * over the API without a console session (which a headless audit job has no way to hold a selected tenant in).
 *
 * <p>Both surfaces share the one merge - {@link RepositoryBrowse#originOf} - which unions a coordinate's
 * format-coordinate document (a hand upload's {@code local-upload} row) and its path-derived document (a fallback fetch's
 * {@code fallback} row, keyed off the request path its verdict sibling shares), so an uploaded artifact, a
 * fallback-fetched one, and a hybrid's locally-shadowed fallback all read back their full acquisition history here,
 * including a no-store fallback's row that survives durably beside transient bytes that never landed (§1: only the two
 * small sections are read, never the artifact body).
 */
@RestController
public class OriginController {

    private final Repositories repositories;

    public OriginController(Repositories repositories) {
        this.repositories = repositories;
    }

    @GetMapping("/api/origin")
    @ResponseBody
    public List<RepositoryBrowse.OriginRow> origin(@RequestParam("repo") String repo,
                                                   @RequestParam(value = "path", defaultValue = "") String path,
                                                   @RequestHeader(value = Repositories.KEY, required = false) String key,
                                                   HttpServletResponse response) {
        String tenant = RepositoryRequests.access(repositories, repo, key, response);
        if (tenant == null) {
            return null;
        }
        // The read is confined to the tenant's named repository store and traversal-guarded exactly as the console
        // origin panel is (RepositoryBrowse.safePrefix), so a crafted path cannot escape the repository subtree.
        return RepositoryBrowse.originOf(repositories.store(tenant, repo), RepositoryBrowse.safePrefix(path));
    }
}
