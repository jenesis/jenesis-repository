package build.jenesis.repository.dependents.web;

import module java.base;
import build.jenesis.repository.closure.spi.Reliance;
import build.jenesis.repository.dependents.spi.DependentsQuery;
import build.jenesis.repository.dependents.spi.DependentsQueryProvider;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.store.ArtifactStore;

/**
 * What depends on a version, the one answer the API, the console and the CLI give: its <b>resolved</b> dependents -
 * the published versions of the tenant whose closure reaches it, each with the path along which it does - and its
 * <b>declared</b> dependents - the versions of the repository whose manifest names its package, each with the
 * requirement it states and whether that admits the version asked about. The two are different facts: a resolved
 * dependent was built against the version, a declared one only names the package, and a requirement is not a version
 * anything was built against.
 *
 * <p>Each half is one bounded page, resumed by its own cursor, so an answer costs the same however many depend on the
 * version. A half that cannot answer says so rather than failing the other: with no version asked there are no
 * resolved dependents to page, with the declared index not installed the declared half says so, and before its first
 * full pass it says when it has not been built.
 */
public final class Dependents {

    /** The most rows of each half one page holds. */
    public static final int MAX_PAGE = Reliance.MAX_PAGE;

    private Dependents() {
    }

    /** What depends on {@code version} of {@code coordinate} of {@code ecosystem}, held by {@code repository}. */
    public record View(String repository, String ecosystem, String coordinate, String version, Resolved resolved,
                       Declared declared) {
    }

    /** The resolved dependents: one page of them, how many index rows it read, and the cursor of the next page,
     *  {@code null} once there is none. {@code null} in a {@link View} asked about no version. */
    public record Resolved(List<Reliance.Dependent> dependents, int examined, String next) {
    }

    /** The declared dependents: whether the declared index is {@code installed}, when its last full pass started -
     *  {@code null} before the first - one page of the declarations, and the cursor of the next page. */
    public record Declared(boolean installed, Instant built, List<Declarations.Row> declarations, String next) {
    }

    /**
     * One page of each half, {@code tenant} being the tenant's store, {@code after} and {@code declaredAfter} each
     * half's cursor ({@code ""} from the start), {@code limit} the rows of each, and {@code readable} the repositories
     * whose resolved dependents the caller may see.
     */
    public static View read(ArtifactStore tenant, String repository, String ecosystem, String coordinate,
                            String version, String after, String declaredAfter, int limit,
                            Predicate<String> readable) throws IOException {
        int rows = Math.max(1, Math.min(limit, MAX_PAGE));
        ArtifactStore store = tenant.scope(repository);
        Resolved resolved = null;
        if (version != null && !version.isBlank()) {
            Reliance.Page page = Reliance.over(store, repository, Optional.of(tenant),
                    name -> Repositories.valid(name) ? Optional.of(tenant.scope(name)) : Optional.empty())
                    .dependents(ecosystem, coordinate, version, after, rows,
                    readable);
            resolved = new Resolved(page.dependents(), page.examined(), page.next().orElse(null));
        }
        return new View(repository, ecosystem, coordinate, version, resolved,
                declared(store, ecosystem, coordinate, version, declaredAfter, rows));
    }

    private static Declared declared(ArtifactStore store, String ecosystem, String coordinate, String version,
                                     String after, int limit) throws IOException {
        Optional<DependentsQueryProvider> provider = DependentsQueryProvider.installed();
        if (provider.isEmpty()) {
            return new Declared(false, null, List.of(), null);
        }
        DependentsQuery query = provider.get().over(store);
        Optional<Instant> built = query.declarationsBuiltAt();
        if (built.isEmpty()) {
            return new Declared(true, null, List.of(), null);
        }
        DependentsQuery.DeclarationPage page = query.declarations(ecosystem, coordinate,
                after.isBlank() ? null : after, limit);
        return new Declared(true, built.get(), Declarations.disclosable(new StoreRepositoryInventory(store),
                page.declarations(), version), page.nextCursor());
    }
}
