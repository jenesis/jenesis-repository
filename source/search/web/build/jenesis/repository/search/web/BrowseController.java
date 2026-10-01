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

    /** Says, on a {@code refresh=true} read of the licence inventory, whether this request started the count or
     *  found one already running - the header every stored-report refresh answers with. */
    public static final String REFRESH_HEADER = "Jenesis-Refresh";

    /**
     * The licence inventory of a repository: how many of its versions declare each licence category and each SPDX
     * id, as the last count left it. The answer is the stored report's state - {@code not-counted} before any count
     * was asked for, {@code running} with when it started, {@code done} with when it finished and the counts, or
     * {@code failed} with the reason - read by one point read, whether or not the repository's full-text search is on.
     *
     * <p>{@code refresh=true} asks for a fresh count: it is started in the background, the {@value #REFRESH_HEADER}
     * header says whether this request started it or found one already running, and the answer is the state as it
     * then stands. The count visits every version, so it never runs on the request; a caller polls this read until
     * the state is no longer {@code running}.
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

    /** One licence-inventory row: the category or SPDX id and the number of versions carrying it. */
    public record LicenseCount(String value, long versions) {
    }

    /**
     * The licence inventory as stored: its {@code state} ({@code not-counted}, {@code running}, {@code done} or
     * {@code failed}), when the count started and finished ({@code null} until it has), why it failed, how many
     * versions it counted, the counts per category and per SPDX id - most versions first - how many rows the count
     * produced, whether the rows shown stop short of them, and - while a count runs or after one failed - the
     * {@code previous} finished count, {@code null} when there was none.
     */
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
