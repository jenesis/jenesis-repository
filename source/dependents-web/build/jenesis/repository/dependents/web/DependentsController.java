package build.jenesis.repository.dependents.web;

import module java.base;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.dependents.spi.DependentsQuery;
import build.jenesis.repository.dependents.spi.DependentsQueryProvider;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.server.kernel.RepositoryRequests;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who declares a dependency on a package, over the declared-dependencies index. The index is a discovered optional
 * module, so this answers {@code 501} when it is absent, and {@code 503} "not yet indexed" until its first full pass
 * has landed, rather than a page that would read as "nothing declares it". Which published versions rely on a version,
 * as their resolved closures reach it, is {@code /api/repository/relied-on}.
 */
@RestController
public class DependentsController {

    private final Repositories repositories;
    private final RepositoryRouting routing;
    // The index is a discovered optional module; empty when absent, so /api/dependents answers 501.
    private final Optional<DependentsQueryProvider> dependentsQuery = DependentsQueryProvider.installed();

    public DependentsController(Repositories repositories, RepositoryRouting routing) {
        this.repositories = repositories;
        this.routing = routing;
    }

    /**
     * One page of the versions whose manifest declares a dependency on {@code package} - a name as its ecosystem spells
     * it, no version - each with the requirement it states, resumed by {@code after}; with a {@code version} of that
     * package beside it, whether each requirement admits it. One small-object shard fetch, never a scan, screened so a
     * version since deleted or withheld is not named. {@code 400} without a package, {@code 501} when no index module
     * is installed, {@code 503} before its first full pass.
     */
    @GetMapping("/api/dependents")
    @ResponseBody
    public DependentsView dependents(@RequestParam("repo") String repo,
                                     @RequestParam(value = "package", required = false) String dependency,
                                     @RequestParam(value = "version", required = false) String version,
                                     @RequestParam(value = "after", defaultValue = "") String after,
                                     @RequestParam(value = "limit", defaultValue = "500") int limit,
                                     HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tenant = RepositoryRequests.access(routing, repo, request, response);
        if (tenant == null) {
            return null;
        }
        if (dependentsQuery.isEmpty()) {
            respond(response, 501, "the declared-dependencies index is not installed on this deployment");
            return null;
        }
        if (dependency == null || dependency.isBlank()) {
            respond(response, 400, "name a package: ?package=<name as its ecosystem spells it>");
            return null;
        }
        DependentsQuery query = dependentsQuery.get().over(repositories.store(tenant, repo));
        Optional<Instant> built = query.declarationsBuiltAt();
        if (built.isEmpty()) {
            respond(response, 503, "the declared dependencies of this repository have not been indexed yet");
            return null;
        }
        DependentsQuery.DeclarationPage page = query.declarations(dependency, after.isBlank() ? null : after,
                Math.max(1, Math.min(limit, Declarations.MAX_PAGE)));
        return new DependentsView(dependency, Declarations.disclosable(
                new StoreRepositoryInventory(repositories.store(tenant, repo)), page.declarations(), version),
                page.nextCursor(), built.get());
    }

    private static void respond(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public void badRequest(HttpServletResponse response) {
        response.setStatus(400);
    }

    /** The versions declaring a dependency on {@code dependency}: the {@code declared} rows, the
     *  {@code nextDeclaredCursor} to resume after ({@code null} on the last page), and {@code declaredLastBuilt}, when
     *  the index's last full pass started. A row's {@code admits} is set only when a version was asked about. */
    public record DependentsView(String dependency, List<Declarations.Row> declared, String nextDeclaredCursor,
                                 Instant declaredLastBuilt) {
    }
}
