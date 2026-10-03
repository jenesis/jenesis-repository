package build.jenesis.repository.search.web;

import module java.base;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.server.kernel.RepositoryRequests;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.compliance.inventory.LicenseReport;
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
 * CLI's {@code browse} and {@code search}, and the repository's licence inventory. Browse pages one level of the
 * served tree; search is the repository's {@link RepositorySearch}, a lookup by name unless the repository's
 * full-text index is switched on; the licence inventory is the stored report {@link LicenseReport} keeps - never an
 * artifact blob, and never a walk of the store.
 */
@RestController
public class BrowseController {

    /** The most immediate children one {@code /api/browse} renders. A directory with an enormous fan-out (a coordinate
     *  with hundreds of thousands of timestamped versions) is navigated into, not scrolled, so the browse pages a
     *  bounded window rather than materialising the child set - the console tree's cap. The response flags whether more
     *  remain. */
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
        // /api/browse is tenant-facing, so the prefix is confined to the served namespace as the console tree confines
        // it: a leading quarantine segment is dropped so the review subtree cannot be enumerated, and one level is
        // paged through the servable-name screen so a held or torn leaf a GET would 404 never appears. The client's
        // path is mapped onto the format's stored tree before the review subtree is screened out, and back on the
        // answer.
        String safe = ServableNames.safePrefix(repositories.formatPath(tenant, repo, ServableNames.safePrefix(prefix)));
        // Ask for exactly the render cap; truncation is the primitive's own outcome (it proved stored children remain
        // past the window), not the screened list's length, which a withheld leaf could leave under the cap while a
        // tail remains.
        StoreRepositoryInventory.ChildPage page = new StoreRepositoryInventory(repositories.store(tenant, repo))
                .children(safe, MAX_CHILDREN, ServableNames.Policy.HIDE_WITHHELD_AND_GONE);
        return new BrowseView(repositories.servedPath(tenant, repo, safe), page.names(), page.truncated());
    }

    /** One bounded page of the repository's search: its {@code mode} ({@code NAME} or {@code FULL_TEXT}), whether the
     *  index answered, the hits and the next cursor. {@code limit} defaults to {@link RepositorySearch#PAGE}, clamped
     *  to the search's ceiling. The mode is the repository's {@code full-text-search} setting, from the node's cached
     *  settings snapshot. */
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

    /** On a {@code refresh=true} read of the licence inventory, whether this request started the count or found one
     *  running - the header every stored-report refresh answers with. */
    public static final String REFRESH_HEADER = "Jenesis-Refresh";

    /**
     * The licence inventory of a repository, as the last count left it: {@code not-counted}, {@code running} with its
     * start, {@code done} with its finish and counts, or {@code failed} with the reason - one point read, whether or
     * not full-text search is on.
     *
     * <p>{@code refresh=true} starts a fresh count in the background; {@value #REFRESH_HEADER} says whether this
     * request started it, and the answer is the state as it then stands. A count visits every version, so it never runs
     * on the request; a caller polls until the state is no longer {@code running}.
     */
    @GetMapping("/api/licenses")
    @ResponseBody
    public LicensesView licenses(@RequestParam("repo") String repo,
                                 @RequestParam(value = "refresh", defaultValue = "false") boolean refresh,
                                 HttpServletRequest request,
                                 HttpServletResponse response) throws IOException {
        String tenant = RepositoryRequests.access(routing, repo, request, response);
        if (tenant == null) {
            return null;
        }
        ArtifactStore store = repositories.store(tenant, repo);
        if (refresh) {
            response.setHeader(REFRESH_HEADER, LicenseReport.start(store) ? "started" : "running");
        }
        return LicensesView.of(LicenseReport.read(store));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public void badRequest(HttpServletResponse response) {
        response.setStatus(400);
    }

    public record BrowseView(String prefix, List<String> entries, boolean truncated) {
    }

    /** One bounded page of search hits: the mode and whether the index answered, the disclosed rows as displays and as
     *  parts, whether matches remain, and the opaque cursor ({@code null} exactly when nothing remains) - the rows
     *  alone could not tell a complete answer from a clamped one. */
    public record SearchView(String mode, boolean indexed, List<String> results, List<Hit> hits, boolean truncated,
                             String nextCursor) {
    }

    /** One hit's parts: ecosystem, coordinate and version, or the path of an artifact without a coordinate. */
    public record Hit(String ecosystem, String coordinate, String version, String path) {
    }

    /** One licence-inventory row: the category or SPDX id and the number of versions carrying it. */
    public record LicenseCount(String value, long versions) {
    }

    /** The licence inventory as stored: {@code state}, when the count started and finished ({@code null} until it has),
     *  why it failed, versions counted, counts per category and SPDX id (most versions first), rows produced, whether
     *  the rows shown stop short, and - while running or after a failure - the {@code previous} finished count, or
     *  {@code null}. */
    public record LicensesView(String state, Instant startedAt, Instant finishedAt, String failure, long versions,
                               List<LicenseCount> categories, List<LicenseCount> licenses, int rows,
                               boolean truncated, LicensesView previous) {

        static LicensesView of(LicenseReport.Inventory inventory) {
            return new LicensesView(inventory.state().name().toLowerCase(Locale.ROOT).replace('_', '-'),
                    inventory.startedAt(), inventory.finishedAt(), inventory.failure(), inventory.versions(),
                    counts(inventory.categories()), counts(inventory.licenses()), inventory.rows(),
                    inventory.truncated(), inventory.previous() == null ? null : of(inventory.previous()));
        }

        private static List<LicenseCount> counts(List<LicenseReport.Count> counts) {
            return counts.stream().map(count -> new LicenseCount(count.value(), count.versions())).toList();
        }
    }
}
