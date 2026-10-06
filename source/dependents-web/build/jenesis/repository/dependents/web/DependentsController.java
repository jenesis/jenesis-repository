package build.jenesis.repository.dependents.web;

import module java.base;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.dependents.spi.DependentsQuery;
import build.jenesis.repository.dependents.spi.DependentsQueryProvider;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.server.kernel.RepositoryRequests;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ServableNames;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The reverse-dependency ("who depends on X") and CVE blast-radius query over the sharded dependents index. The index
 * is a discovered optional module, so this answers {@code 501} when it is absent. When the module is
 * installed but its sweep has not built the index for the repository yet (freshly enabled, or not yet run over
 * pre-existing artifacts) it answers {@code 503} "not yet built" rather than a false-complete empty answer, so a
 * client never mistakes a not-yet-derived index for a definitive empty blast radius.
 */
@RestController
public class DependentsController {

    private final Repositories repositories;
    private final RepositoryRouting routing;
    // The reverse-dependency index is a discovered optional module; empty when absent, so /api/dependents answers 501.
    private final Optional<DependentsQueryProvider> dependentsQuery = DependentsQueryProvider.installed();

    public DependentsController(Repositories repositories, RepositoryRouting routing) {
        this.repositories = repositories;
        this.routing = routing;
    }

    /**
     * With a {@code coordinate} it answers the artifacts whose recorded dependency tree names it - so pointing it at
     * a freshly vulnerable coordinate lists exactly what is affected; without one it lists the coordinates the index
     * holds a dependent for. With a {@code package} - a name as its ecosystem spells it, no version - it answers the
     * declared tier instead: one page of the versions whose manifest declares a dependency on it, each with the
     * requirement it states, resumed by {@code after} - and with a {@code version} of that package beside it, whether
     * each requirement admits it. A single small-object shard fetch, never a scan. Answers {@code 501} when no
     * reverse-dependency module is installed, and {@code 503} when the module is installed but the index has not been
     * built for this repository yet - the index would have nothing, or nothing yet, to read.
     */
    @GetMapping("/api/dependents")
    @ResponseBody
    public DependentsView dependents(@RequestParam("repo") String repo,
                                     @RequestParam(value = "coordinate", required = false) String coordinate,
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
            respondDependentsNotInstalled(response);
            return null;
        }
        DependentsQuery query = dependentsQuery.get().over(repositories.store(tenant, repo));
        if (dependency != null && !dependency.isBlank()) {
            return declarations(query, repositories.store(tenant, repo), dependency, version, after, limit,
                    response);
        }
        if (!query.built()) {
            respondDependentsNotBuilt(response);
            return null;
        }
        // When the sweep last rebuilt the index, so a client judges how fresh the blast radius is - null only for a
        // torn or foreign marker. The read path never rebuilds; that is the sweep's job under its lease.
        Instant lastBuilt = query.builtAt().orElse(null);
        if (coordinate == null || coordinate.isBlank()) {
            // Enumerate-all: one bounded page of the coordinates the index holds, resumed by the opaque ?after cursor.
            int pageLimit = Math.max(1, Math.min(limit, MAX_PAGE));
            DependentsQuery.CoordinatePage page = query.coordinates(after.isBlank() ? null : after, pageLimit);
            // A withheld coordinate is screened out through the servable-name seam under HIDE_WITHHELD, each versioned
            // coordinate neutralised to its group:name:version display form; one the inventory cannot place as a held
            // member stays listed, so only a held member's name is removed.
            StoreRepositoryInventory inventory = new StoreRepositoryInventory(repositories.store(tenant, repo));
            List<String> disclosable = new ArrayList<>();
            for (String held : page.coordinates()) {
                if (inventory.disclosableDisplay(DependentsQuery.neutralise(held),
                        ServableNames.Policy.HIDE_WITHHELD)) {
                    disclosable.add(held);
                }
            }
            return new DependentsView(null, null, disclosable, page.nextCursor(), lastBuilt, null, null, null, null);
        }
        RepositoryRequests.rejectTraversal(coordinate);
        // The same screen for the single-coordinate blast radius, so a withheld dependent's existence and dependency
        // are not disclosed; a probe that throws drops that name.
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(repositories.store(tenant, repo));
        List<String> dependents = new ArrayList<>();
        for (String dependent : query.dependents(coordinate)) {
            if (inventory.disclosableDisplay(DependentsQuery.neutralise(dependent),
                    ServableNames.Policy.HIDE_WITHHELD)) {
                dependents.add(dependent);
            }
        }
        return new DependentsView(coordinate, dependents, null, null, lastBuilt, null, null, null, null);
    }

    /** The declared tier's answer for one package: a page screened through {@link Declarations}, so a version since
     *  deleted or withheld is not named, and {@code 503} until the tier's first full pass has landed - before it the
     *  tier holds only what the passes since publishing have visited, and an empty page would read as "nothing
     *  declares it". */
    private DependentsView declarations(DependentsQuery query, ArtifactStore store, String dependency, String version,
                                        String after, int limit, HttpServletResponse response) throws IOException {
        Optional<Instant> built = query.declarationsBuiltAt();
        if (built.isEmpty()) {
            response.setStatus(503);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("the declared dependencies of this repository have not been indexed yet");
            return null;
        }
        DependentsQuery.DeclarationPage page = query.declarations(dependency, after.isBlank() ? null : after,
                Math.max(1, Math.min(limit, Declarations.MAX_PAGE)));
        return new DependentsView(null, null, null, null, null, dependency,
                Declarations.disclosable(new StoreRepositoryInventory(store), page.declarations(), version),
                page.nextCursor(), built.get());
    }

    /** The largest coordinate page served, so a caller's {@code limit} cannot ask the index to materialise an unbounded
     *  window; a caller past it follows the {@code nextCoordinatesCursor}. */
    private static final int MAX_PAGE = 5000;

    /** With no reverse-dependency module installed the query answers 501, after the auth check so 401/403 still
     *  precede - there is no index to read from. */
    private static void respondDependentsNotInstalled(HttpServletResponse response) throws IOException {
        response.setStatus(501);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("the reverse-dependency index is not installed on this deployment");
    }

    /** The module is installed but its {@code Lease}-guarded sweep has not built the index for this repository yet
     *  (freshly enabled, or not yet run over pre-existing artifacts), so a blast-radius query would read a
     *  false-complete empty answer. Answer {@code 503} - "not yet built", a transient state the next sweep clears -
     *  rather than serve an empty result as if it were authoritative. Distinct from the {@code 501} not-installed and
     *  from a genuinely-empty built index, which answers {@code 200} with no dependents. */
    private static void respondDependentsNotBuilt(HttpServletResponse response) throws IOException {
        response.setStatus(503);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("the reverse-dependency index has not been built for this repository yet");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public void badRequest(HttpServletResponse response) {
        response.setStatus(400);
    }

    /** A reverse-dependency answer: with a {@code coordinate}, the {@code dependents} that pull it in (blast radius);
     *  without one, one bounded page of the {@code coordinates} the index holds plus {@code nextCoordinatesCursor} -
     *  the opaque token to resume the enumerate-all after this page ({@code null} on the last page, and always
     *  {@code null} on the single-coordinate answer). The unused half is {@code null} so a client sees which question
     *  was answered. {@code lastBuilt} is the instant the scheduled sweep last rebuilt the index (the staleness
     *  signal), {@code null} only when the sweep marker's body does not parse as an instant (a torn or foreign object).
     *  With a {@code package}, only the declared half is set: the {@code declared} rows, the {@code nextDeclaredCursor}
     *  to resume after, and {@code declaredLastBuilt}, when the tier's last full pass started - declared rows are
     *  requirements a manifest states, never resolved dependents, so they are a separate answer rather than more
     *  {@code dependents}. A row's {@code admits} is set only when a version was asked about. */
    public record DependentsView(String coordinate, List<String> dependents, List<String> coordinates,
                                 String nextCoordinatesCursor, Instant lastBuilt, String dependency,
                                 List<Declarations.Row> declared, String nextDeclaredCursor,
                                 Instant declaredLastBuilt) {
    }
}
