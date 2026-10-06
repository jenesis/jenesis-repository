package build.jenesis.repository.dependents.web;

import module java.base;
import build.jenesis.repository.server.RepositoryAuthorizationManager;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.RepositoryRequests;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * What depends on a version a repository holds - its resolved and its declared dependents ({@link Dependents}) - as a
 * repository's read: the repository's rights, and the resolved dependents only of the repositories the caller may
 * read. Each half one bounded page resumed by its own cursor; a half that cannot answer says so in the answer, never
 * by failing the request.
 */
@RestController
public class DependentsController {

    private final Repositories repositories;
    private final RepositoryRouting routing;

    public DependentsController(Repositories repositories, RepositoryRouting routing) {
        this.repositories = repositories;
        this.routing = routing;
    }

    /**
     * What depends on {@code version} of {@code coordinate} of {@code ecosystem} in {@code repo}: up to {@code limit}
     * (at most {@value Dependents#MAX_PAGE}) resolved dependents after {@code after} - none without a version - and as
     * many declared ones after {@code declaredAfter}, each saying, given a version, whether its requirement admits it.
     * {@code 400} for a missing ecosystem or coordinate, or a cursor that is no row.
     */
    @SuppressWarnings("unchecked")
    @GetMapping("/api/repository/dependents")
    @ResponseBody
    public Dependents.View dependents(@RequestParam("repo") String repo,
                                      @RequestParam("ecosystem") String ecosystem,
                                      @RequestParam("coordinate") String coordinate,
                                      @RequestParam(value = "version", required = false) String version,
                                      @RequestParam(value = "after", defaultValue = "") String after,
                                      @RequestParam(value = "declaredAfter", defaultValue = "") String declaredAfter,
                                      @RequestParam(value = "limit", defaultValue = "50") int limit,
                                      HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tenant = RepositoryRequests.access(routing, repo, request, response);
        if (tenant == null) {
            return null;
        }
        if (ecosystem.isBlank() || coordinate.isBlank() || after.contains("/")) {
            response.setStatus(400);
            return null;
        }
        Predicate<String> readable = request.getAttribute(RepositoryAuthorizationManager.READS_REPOSITORY)
                instanceof Predicate<?> reads ? name -> ((Predicate<String>) reads).test(name) : repo::equals;
        return Dependents.read(repositories.root().scope(tenant), repo, ecosystem, coordinate, version, after,
                declaredAfter, limit, readable);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public void badRequest(HttpServletResponse response) {
        response.setStatus(400);
    }
}
