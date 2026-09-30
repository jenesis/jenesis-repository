package build.jenesis.repository.search.web;

import module java.base;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.server.kernel.RepositoryRequests;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.search.LicenseFacet;
import build.jenesis.repository.search.SearchQuery;
import build.jenesis.repository.search.service.RepositorySearch;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The browse and search reads over a tenant's named repository, driving the console's tree and search bar and the
 * CLI's {@code browse} and {@code search}. Browse pages one level of the served tree; search is the repository's
 * {@link RepositorySearch}, a lookup by name unless the repository's full-text index is switched on - never an
 * artifact blob, and never a walk of the store.
 */
@RestController
public class BrowseController {

    /** The most immediate children a single {@code /api/browse} renders. A directory with an enormous fan-out (a
     *  coordinate with hundreds of thousands of timestamped versions) is navigated into, not scrolled, so the browse
     *  pages a bounded window through the store rather than materialising the whole child set in heap per request - the
     *  same cap the free console's tree browse applies. The response flags whether more children remain past it. */
    private static final int MAX_CHILDREN = 1000;

    private final Repositories repositories;
    private final RepositoryRouting routing;
    private final RepositorySearch search;

    public BrowseController(Repositories repositories, RepositoryRouting routing, RepositorySearch search) {
        this.repositories = repositories;
        this.routing = routing;
        this.search = search;
    }

    @GetMapping("/api/browse")
    @ResponseBody
    public BrowseView browse(@RequestParam("repo") String repo,
                             @RequestParam(value = "prefix", defaultValue = "") String prefix,
                             HttpServletRequest http,
                             HttpServletResponse response) throws IOException {
        String tenant = RepositoryRequests.access(routing, repo, http, response);
        if (tenant == null) {
            return null;
        }
        RepositoryRequests.rejectTraversal(prefix);
        // /api/browse is TENANT-FACING. Confine the query-supplied prefix to the served namespace
        // exactly as the console tree does - drop a leading "quarantine" segment so a crafted prefix cannot enumerate the
        // withheld-artifact review subtree - then page one level through the servable-name seam's WG screen so a held or
        // torn leaf a GET would 404 never appears. This closes an /api/browse gap (there was no leaf screen and no
        // quarantine-subtree refusal: rejectTraversal only blocked '..', so prefix=/quarantine/... enumerated it).
        // The prefix is the path a client names within the repository; the tree it pages is the one the format lays
        // out, so the mount goes back on before the review subtree is screened out of it, and comes off the answer.
        String safe = safePrefix(repositories.formatPath(tenant, repo, safePrefix(prefix)));
        // Ask for exactly the render cap: the screened enumeration bounds itself to that window, so the whole (possibly
        // enormous) child set never lands in heap on one request. Truncation is the primitive's own outcome - it proved
        // stored children remain past the window - NOT a post-screen list length: a withheld/torn leaf or the suppressed
        // quarantine child screened out of the window would otherwise leave a full-but-under-cap names list, reporting
        // truncated:false while the tail past the window is silently dropped.
        StoreRepositoryInventory.ChildPage page = new StoreRepositoryInventory(repositories.store(tenant, repo))
                .children(safe, MAX_CHILDREN, ServableNames.Policy.HIDE_WITHHELD_AND_GONE);
        return new BrowseView(repositories.servedPath(tenant, repo, safe), page.names(), page.truncated());
    }

    /**
     * One bounded page of the repository's search: its {@code mode} ({@code NAME} or {@code FULL_TEXT}), whether the
     * full-text index answered, the hits and the cursor to the next page. {@code limit} defaults to
     * {@link RepositorySearch#PAGE} and is clamped to the search's own ceiling. The mode is the repository's
     * {@code full-text-search} setting, resolved from the node's cached settings snapshot over the repository's, the
     * tenant's and the deployment's settings documents - one object per module under a constant prefix.
     */
    @GetMapping("/api/search")
    @ResponseBody
    public SearchView search(@RequestParam("repo") String repo,
                             @RequestParam(value = "q", defaultValue = "") String query,
                             @RequestParam(value = "cursor", required = false) String cursor,
                             @RequestParam(value = "limit", required = false) Integer limit,
                             HttpServletRequest http,
                             HttpServletResponse response) throws IOException {
        String tenant = RepositoryRequests.access(routing, repo, http, response);
        if (tenant == null) {
            return null;
        }
        RepositorySearch.Answer answer = search.search(repositories.store(tenant, repo), tenant + '/' + repo,
                config(tenant, repo), query, cursor, limit == null ? RepositorySearch.PAGE : limit);
        List<String> results = new ArrayList<>();
        List<Hit> hits = new ArrayList<>();
        for (SearchQuery.Hit hit : answer.hits()) {
            results.add(hit.display());
            hits.add(new Hit(hit.ecosystem(), hit.coordinate(), hit.version(), hit.path()));
        }
        return new SearchView(answer.mode().name(), answer.indexed(), results, hits, answer.truncated(),
                answer.nextCursor());
    }

    /** The repository's effective configuration, which decides how it answers a search. */
    private UnaryOperator<String> config(String tenant, String repo) {
        return key -> repositories.live().effective(tenant, repo, key, null);
    }

    /**
     * The license inventory over a repository: facet counts per license category and per SPDX id, counted by the
     * repository's full-text index. Each row drills down through {@code /api/search?q=category:<value>} or
     * {@code license:<value>} to the coordinates behind it. Available only while the repository's full-text search is
     * on and its index built ({@code indexed=false} otherwise, with empty facets); without it the view reports itself
     * unavailable rather than scanning every artifact's metadata on the request path. Whether it is on is read from
     * the settings documents as {@link #search} reads it.
     */
    @GetMapping("/api/licenses")
    @ResponseBody
    public LicensesView licenses(@RequestParam("repo") String repo,
                                 HttpServletRequest request,
                                 HttpServletResponse response) throws IOException {
        String tenant = RepositoryRequests.access(routing, repo, request, response);
        if (tenant == null) {
            return null;
        }
        Optional<List<LicenseFacet>> facets = search.licenses(repositories.store(tenant, repo), tenant + '/' + repo,
                config(tenant, repo));
        if (facets.isEmpty()) {
            return new LicensesView(false, List.of(), List.of());
        }
        List<LicenseCount> categories = new ArrayList<>();
        List<LicenseCount> licenses = new ArrayList<>();
        for (LicenseFacet facet : facets.get()) {
            (facet.kind().equals(LicenseFacet.CATEGORY) ? categories : licenses)
                    .add(new LicenseCount(facet.value(), facet.count()));
        }
        return new LicensesView(true, categories, licenses);
    }

    /** Normalise a query-supplied browse prefix into the leading-slash form the inventory's {@code children} expects,
     *  dropping any empty / {@code .} / {@code ..} / backslash-bearing segment AND refusing a LEADING
     *  {@link ServableNames#QUARANTINE quarantine} segment - the reserved withheld-artifact review subtree a GET never
     *  serves, so a tenant-facing {@code /api/browse} must not let a crafted prefix enumerate it (a deeper
     *  {@code quarantine} is a legitimate artifact-path segment and is kept). Mirrors the console
     *  {@code RepositoryBrowse.safePrefix} so the REST browse confines exactly as the console tree does. */
    private static String safePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return "";
        }
        StringBuilder safe = new StringBuilder();
        for (String segment : prefix.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.indexOf('\\') >= 0) {
                continue;
            }
            if (safe.length() == 0 && ServableNames.reviewSubtree(segment)) {
                continue;
            }
            safe.append('/').append(segment);
        }
        return safe.toString();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public void badRequest(HttpServletResponse response) {
        response.setStatus(400);
    }

    public record BrowseView(String prefix, List<String> entries, boolean truncated) {
    }

    /** One bounded page of search hits: the mode the repository answers in and whether its full-text index answered
     *  this page, the disclosed rows as {@code coordinate:version} displays (a path for an artifact with no
     *  coordinate) and as their parts, whether matches remain past this page, and the opaque cursor to resume after -
     *  {@code null} exactly when nothing remains. The row list alone could not tell a complete answer from a clamped
     *  one. */
    public record SearchView(String mode, boolean indexed, List<String> results, List<Hit> hits, boolean truncated,
                             String nextCursor) {
    }

    /** One hit's parts: the ecosystem, coordinate and version of a published version, or the path of an artifact
     *  with no coordinate. */
    public record Hit(String ecosystem, String coordinate, String version, String path) {
    }

    /** One license-inventory facet row: the category or SPDX id and the number of coordinates carrying it. */
    public record LicenseCount(String value, long count) {
    }

    /** The license inventory: whether the index backed it, the per-category counts and the per-SPDX-id counts. */
    public record LicensesView(boolean indexed, List<LicenseCount> categories, List<LicenseCount> licenses) {
    }
}
