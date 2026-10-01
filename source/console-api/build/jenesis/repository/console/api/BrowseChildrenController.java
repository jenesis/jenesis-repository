package build.jenesis.repository.console.api;

import module java.base;

import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.RepositoryRequests;
import build.jenesis.repository.ui.store.RepositoryBrowse;
import build.jenesis.repository.walk.TraversalException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The folder probe over the API: one window of the immediate children under a path in a tenant's repository, each
 * marked folder or artifact with its size, and the cursor past them. The key-header twin of the console's folder screen
 * ({@code /ui/repositories/{repo}/browse/children}), reaching the same {@link RepositoryBrowse#page}; the command
 * line's {@code browse children} drives it.
 *
 * <p>Paged by cursor, never a whole level: at most {@link #MAX_LIMIT} children ({@link #DEFAULT_LIMIT} unless asked),
 * and {@code next} - the child name to pass back as {@code after} - is present exactly when more remain. Paths are the
 * client's, mapped onto the format's stored tree and back, as {@code /api/browse} maps them.
 */
@RestController
public class BrowseChildrenController {

    /** The children one window lists when the caller names no limit. */
    static final int DEFAULT_LIMIT = 200;

    /** The most children one window lists - the console folder screen's own bound. */
    static final int MAX_LIMIT = 1000;

    private final Repositories repositories;
    private final RepositoryRouting routing;

    public BrowseChildrenController(Repositories repositories, RepositoryRouting routing) {
        this.repositories = repositories;
        this.routing = routing;
    }

    /** One window of a folder: the folder as the client names it, its children, and the cursor past them. */
    public record ChildrenView(String prefix, List<Child> children, String next) {
    }

    /** One child: its name, its path within the repository, whether it is a folder, and its size in bytes - a folder's
     *  rolled-up total, {@code -1} while unknown. */
    public record Child(String name, String path, boolean folder, long bytes) {
    }

    /** The window of children under {@code prefix} after {@code after}. A path naming no folder is a {@code 404}, so a
     *  typo does not read as an empty folder; the repository root is never missing. */
    @GetMapping("/api/browse/children")
    @ResponseBody
    public ChildrenView children(@RequestParam("repo") String repo,
                                 @RequestParam(value = "prefix", defaultValue = "") String prefix,
                                 @RequestParam(value = "after", required = false) String after,
                                 @RequestParam(value = "limit", required = false) Integer limit,
                                 HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tenant = RepositoryRequests.access(routing, repo, request, response);
        if (tenant == null) {
            return null;
        }
        RepositoryRequests.rejectTraversal(prefix);
        String safe = RepositoryBrowse.safePrefix(
                repositories.formatPath(tenant, repo, RepositoryBrowse.safePrefix(prefix)));
        int window = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        RepositoryBrowse.BrowsePage page;
        try {
            page = RepositoryBrowse.page(repositories.store(tenant, repo), safe, after, window);
        } catch (TraversalException malformed) {
            response.sendError(400, "after must name one child of the folder: " + malformed.getMessage());
            return null;
        }
        if (page.entries().isEmpty() && page.next() == null && (after == null || after.isEmpty())
                && !safe.isEmpty()) {
            response.sendError(404, "No folder '" + prefix + "' in repository '" + repo + "'.");
            return null;
        }
        List<Child> children = new ArrayList<>();
        for (RepositoryBrowse.BrowseEntry entry : page.entries()) {
            children.add(new Child(entry.name(), repositories.servedPath(tenant, repo, entry.path()), entry.folder(),
                    entry.bytes()));
        }
        return new ChildrenView(repositories.servedPath(tenant, repo, safe), children, page.next());
    }
}
