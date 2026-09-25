package build.jenesis.repository.format.lifecycle.web;

import module java.base;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.server.kernel.RepositoryRequests;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ServableNames;
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

    private final Repositories repositories;
    private final AuditTrail audit;

    public LifecycleController(Repositories repositories, AuditTrail audit) {
        this.repositories = repositories;
        this.audit = audit;
    }

    /**
     * List the marked versions of a repository - a page of the whole repository, or one coordinate when
     * {@code coordinate} is given - so an operator can review the deprecations and yanks in place.
     *
     * <p>The whole-repository form is paged by {@code after}/{@code limit}: a mark exists per deprecated or yanked
     * version, so the answer is sized by what the repository holds. It answers with the cursor that continues it, and
     * a caller wanting everything follows that cursor - which is what the CLI does.
     */
    @GetMapping("/api/lifecycle")
    public LifecycleView list(@RequestParam("repository") String repository,
                              @RequestParam(value = "coordinate", required = false) String coordinate,
                              @RequestParam(value = "after", required = false) String after,
                              @RequestParam(value = "limit", required = false) Integer limit,
                              @RequestHeader(value = Repositories.KEY, required = false) String key,
                              HttpServletResponse response) throws IOException {
        String tenant = access(repository, key, response);
        if (tenant == null) {
            return null;
        }
        // P-Q (plan §8 Q3): served-view parity - a withheld version's lifecycle mark must not be disclosed on this
        // served listing. Each mark is routed through the servable-name enumeration seam
        // (inventory.disclosableDisplay under HIDE_WITHHELD: the membership policy, resolving the mark's ecosystem by
        // the shared bounded published/ probe, stats no blob), so a held coordinate:version's mark drops out while a
        // deprecated-but-servable mark - or a ghost the inventory cannot place as a held member - stays listed.
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(repositories.store(tenant, repository));
        List<FlagView> flags = new ArrayList<>();
        if (coordinate == null || coordinate.isBlank()) {
            Lifecycle.Page page = Lifecycle.page(repositories.store(tenant, repository),
                    (coord, version) -> inventory.disclosableDisplay(coord + ":" + version,
                            ServableNames.Policy.HIDE_WITHHELD),
                    after, pageLimit(limit));
            for (Lifecycle.Entry entry : page.entries()) {
                flags.add(new FlagView(entry.coordinate(), entry.version(),
                        entry.flag().state().name().toLowerCase(Locale.ROOT), entry.flag().message()));
            }
            return new LifecycleView(flags, page.next() != null, page.next());
        } else {
            RepositoryRequests.rejectTraversal(coordinate);
            for (var marked : Lifecycle.versions(repositories.store(tenant, repository), coordinate).entrySet()) {
                String version = marked.getKey();
                if (inventory.disclosableDisplay(coordinate + ":" + version, ServableNames.Policy.HIDE_WITHHELD)) {
                    flags.add(new FlagView(coordinate, version,
                            marked.getValue().state().name().toLowerCase(Locale.ROOT), marked.getValue().message()));
                }
            }
        }
        // One coordinate's marks are bounded by that coordinate's versions, so this form is answered whole and says
        // so: no cursor, and nothing was cut short.
        return new LifecycleView(flags, false, null);
    }

    /** The page size, defaulted and clamped - an unclamped {@code limit} would hand the caller back the unbounded
     *  read the paging exists to remove. */
    private static int pageLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.clamp(limit, 1, MAX_LIMIT);
    }

    /** What a caller that names no {@code limit} gets. */
    private static final int DEFAULT_LIMIT = 200;

    /** The most marks one call will answer with, however large a {@code limit} is asked for. */
    private static final int MAX_LIMIT = 1_000;

    /** Mark a coordinate/version deprecated or yanked. {@code state} is {@code deprecated} or {@code yanked}; the
     *  optional {@code message} is the operator's note surfaced to clients (npm's deprecation text). */
    @PostMapping("/api/lifecycle")
    public void mark(@RequestParam("repository") String repository,
                     @RequestParam("coordinate") String coordinate,
                     @RequestParam("version") String version,
                     @RequestParam("state") String state,
                     @RequestParam(value = "message", required = false) String message,
                     @RequestHeader(value = Repositories.KEY, required = false) String key,
                     HttpServletResponse response) throws IOException {
        String tenant = access(repository, key, response);
        if (tenant == null) {
            return;
        }
        RepositoryRequests.rejectTraversal(coordinate);
        RepositoryRequests.rejectTraversal(version);
        Lifecycle.State parsed = Lifecycle.State.parse(state).orElse(null);
        if (parsed == null) {
            response.setStatus(400);
            return;
        }
        // A mark is refused on a repository whose format shows it to no client: stored, it would read as done while
        // every client went on offering the version exactly as before.
        Optional<RepositoryType> type = repositories.type(tenant, repository);
        if (type.isPresent() && type.get().formats().stream().noneMatch(RepositoryFormat::surfacesLifecycleMarks)) {
            response.setStatus(422);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("A " + type.get().name() + " repository shows a lifecycle mark to no client: "
                    + "its format has no metadata a client reads a deprecation or a yank from, so the mark is "
                    + "refused rather than stored where nobody would see it.");
            return;
        }
        Lifecycle.mark(repositories.store(tenant, repository), coordinate, version,
                new Lifecycle.Flag(parsed, message == null ? "" : message));
        audit(key, "lifecycle." + parsed.name().toLowerCase(Locale.ROOT),
                repository + "/" + coordinate + "@" + version);
        response.setStatus(200);
    }

    /** Clear a coordinate/version's mark. {@code 200} whether or not a mark was present (the end state is the same). */
    @DeleteMapping("/api/lifecycle")
    public void clear(@RequestParam("repository") String repository,
                      @RequestParam("coordinate") String coordinate,
                      @RequestParam("version") String version,
                      @RequestHeader(value = Repositories.KEY, required = false) String key,
                      HttpServletResponse response) throws IOException {
        String tenant = access(repository, key, response);
        if (tenant == null) {
            return;
        }
        RepositoryRequests.rejectTraversal(coordinate);
        RepositoryRequests.rejectTraversal(version);
        if (Lifecycle.clear(repositories.store(tenant, repository), coordinate, version)) {
            audit(key, "lifecycle.clear", repository + "/" + coordinate + "@" + version);
        }
        response.setStatus(200);
    }

    /** A traversal-unsafe repository, coordinate or version name is a {@code 400}, not a {@code 500}. */
    @ExceptionHandler(IllegalArgumentException.class)
    public void badRequest(HttpServletResponse response) {
        response.setStatus(400);
    }

    /**
     * Validates the named repository and resolves the request's tenant from the {@code Jenesis-Repository-Key} header,
     * answering {@code 400} for a traversal-unsafe repository or tenant name and returning {@code null} so the caller
     * returns at once. Rights are enforced by Spring Security before the request reaches the controller, so this makes
     * no authorization decision - the same helper the monolith's {@code RepositoryRequests} carried, kept private to the
     * adapter so the surface stays a thin HTTP layer over the domain.
     */
    private String access(String repository, String key, HttpServletResponse response) {
        if (!Repositories.valid(repository)) {
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

    private void audit(String key, String action, String target) {
        String tenant = repositories.tenant(key);
        audit.record(tenant, key == null ? "anonymous" : Authorization.hash(key), action, target);
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
