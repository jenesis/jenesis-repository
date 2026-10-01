package build.jenesis.repository.format.lifecycle.web;

import module java.base;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.server.kernel.RepositoryRequests;
import build.jenesis.repository.server.spi.Authorization;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator's version-lifecycle surface: mark a hosted version <b>deprecated</b> or <b>yanked</b>, clear the mark,
 * or list what is marked, so a repository's own admins can warn consumers off a bad release (or withdraw it) without
 * unpublishing it. The mark is a small per-tenant metadata object written through the repository's scoped store
 * ({@link Lifecycle}), and the format the coordinate belongs to surfaces it natively on the next read - npm's
 * {@code deprecated} warning string, Cargo's {@code yanked} flag - so an ordinary client warns or skips the version
 * with no manager-specific protocol. The endpoint is format-agnostic: it takes the {@code coordinate} exactly as it
 * appears in that format's artifact path (an npm package name, a Cargo {@code <registry>/<crate>} pair) and stores the
 * flag beside the artifact; a format that has no native lifecycle signal simply ignores a mark it never reads.
 *
 * <p>Peeled out of the composition root into its own thin {@code web} adapter and contributed through the
 * {@code ServerModuleProvider} seam (see {@link LifecycleWebModule}); with this module absent the server carries none of
 * the lifecycle surface. Every route is under {@code /api/}, so the security chain requires {@code manage:read} (the
 * GET) or {@code manage:write} (the mark and clear) before the request is reached, and the tenant is the acting key's
 * own - a tenant marks its own repositories' versions, never another's. A mark and a clear are privileged mutations, so
 * each writes an audit event; a traversal-unsafe repository, coordinate or version name is a {@code 400}.
 */
@RestController
public class LifecycleController {

    private final LifecycleMarks marks;
    private final RepositoryRouting routing;

    public LifecycleController(LifecycleMarks marks, RepositoryRouting routing) {
        this.marks = marks;
        this.routing = routing;
    }

    /**
     * List the marked versions of a repository - a page of the whole repository, or one coordinate when
     * {@code coordinate} is given - so an operator can review the deprecations and yanks in place.
     *
     * <p>The whole-repository form is paged by {@code after}/{@code limit}: a mark exists per deprecated or yanked
     * version, so the answer is sized by what the repository holds. It answers with the cursor that continues it, and
     * a caller wanting everything follows that cursor - which is what the CLI does. One coordinate's marks are bounded
     * by that coordinate's versions, so that form is answered whole and says so: no cursor, nothing cut short.
     */
    @GetMapping("/api/lifecycle")
    public LifecycleView list(@RequestParam("repository") String repository,
                              @RequestParam(value = "coordinate", required = false) String coordinate,
                              @RequestParam(value = "after", required = false) String after,
                              @RequestParam(value = "limit", required = false) Integer limit,
                              HttpServletRequest request,
                              HttpServletResponse response) throws IOException {
        String tenant = RepositoryRequests.access(routing, repository, request, response);
        if (tenant == null) {
            return null;
        }
        if (coordinate == null || coordinate.isBlank()) {
            LifecycleMarks.Page page = marks.page(tenant, repository, after, limit);
            return new LifecycleView(views(page.marks()), page.next() != null, page.next());
        }
        return new LifecycleView(views(marks.coordinate(tenant, repository, coordinate)), false, null);
    }

    private static List<FlagView> views(List<LifecycleMarks.Mark> marks) {
        return marks.stream().map(mark -> new FlagView(mark.coordinate(), mark.version(),
                mark.state().name().toLowerCase(Locale.ROOT), mark.message())).toList();
    }

    /** Mark a coordinate/version deprecated or yanked. {@code state} is {@code deprecated} or {@code yanked}; the
     *  optional {@code message} is the operator's note surfaced to clients (npm's deprecation text). */
    @PostMapping("/api/lifecycle")
    public void mark(@RequestParam("repository") String repository,
                     @RequestParam("coordinate") String coordinate,
                     @RequestParam("version") String version,
                     @RequestParam("state") String state,
                     @RequestParam(value = "message", required = false) String message,
                     @RequestHeader(value = Repositories.KEY, required = false) String key,
                     HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tenant = RepositoryRequests.access(routing, repository, request, response);
        if (tenant == null) {
            return;
        }
        Lifecycle.State parsed = Lifecycle.State.parse(state).orElse(null);
        if (parsed == null) {
            response.setStatus(400);
            return;
        }
        Optional<String> refused = marks.mark(tenant, repository, coordinate, version, parsed, message, actor(key));
        if (refused.isPresent()) {
            response.setStatus(422);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write(refused.get());
            return;
        }
        response.setStatus(200);
    }

    /** Clear a coordinate/version's mark. {@code 200} whether or not a mark was present (the end state is the same). */
    @DeleteMapping("/api/lifecycle")
    public void clear(@RequestParam("repository") String repository,
                      @RequestParam("coordinate") String coordinate,
                      @RequestParam("version") String version,
                      @RequestHeader(value = Repositories.KEY, required = false) String key,
                      HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tenant = RepositoryRequests.access(routing, repository, request, response);
        if (tenant == null) {
            return;
        }
        marks.clear(tenant, repository, coordinate, version, actor(key));
        response.setStatus(200);
    }

    /** A traversal-unsafe repository, coordinate or version name is a {@code 400}, not a {@code 500}. */
    @ExceptionHandler(IllegalArgumentException.class)
    public void badRequest(HttpServletResponse response) {
        response.setStatus(400);
    }

    /** Who a key's change is recorded as: its hash, never the key. */
    private static String actor(String key) {
        return key == null ? "anonymous" : Authorization.hash(key);
    }

    /** A page of a repository's marked versions (or, for a single coordinate, all of them). {@code next} carries the
     *  cursor to continue from and is {@code null} at the end; {@code more} says the same thing where a caller finds
     *  a flag easier to read than a null check. */
    public record LifecycleView(List<FlagView> flags, boolean more, String next) {
    }

    /** One marked version: its {@code coordinate}, {@code version}, {@code state} and operator {@code message}. */
    public record FlagView(String coordinate, String version, String state, String message) {
    }
}
