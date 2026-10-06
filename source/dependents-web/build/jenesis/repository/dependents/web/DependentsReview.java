package build.jenesis.repository.dependents.web;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.dependents.spi.DependentsQuery;
import build.jenesis.repository.dependents.spi.DependentsQueryProvider;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.ui.store.ConsoleActor;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.ui.store.TenantScope;
import io.micrometer.observation.ObservationRegistry;

/**
 * What the reverse-dependency index can answer for the console, read through the tenant a call runs in.
 *
 * <p>This module's own console read service, since the index is this module's; {@link TenantScope} shares the
 * scoping, the audit attribution and the repository guard.
 *
 * <p>Read-only, so it takes the three-argument constructor: nothing here records an audit event.
 */
public class DependentsReview extends TenantScope {

    public DependentsReview(ArtifactStore repositoryStore, CurrentTenant current, ObservationRegistry observations) {
        super(repositoryStore, current, observations);
    }

    /** Whether the reverse-dependency index module is installed on this deployment (the console hides the panel when
     *  it is not). */
    public boolean dependentsAvailable() {
        return DependentsQueryProvider.installed().isPresent();
    }

    /** The instant the reverse-dependency index was last rebuilt for a repository - the scheduled sweep's completion
     *  stamp (the staleness line on the dependents panel), so the operator sees how fresh the blast radius
     *  is instead of guessing whether an empty index means "nothing depends on anything" or "never built". {@code null}
     *  when the module is absent, the index was never built, or the sweep marker's body does not parse as an instant -
     *  rendered as "not yet built", never as freshly built. A single small-object read; the render never rebuilds. */
    public Instant dependentsBuiltAt(String repository) throws IOException {
        Optional<DependentsQueryProvider> provider = DependentsQueryProvider.installed();
        if (provider.isEmpty()) {
            return null;
        }
        return provider.get().over(scope(repository)).builtAt().orElse(null);
    }

    /** The artifacts whose recorded dependency tree names {@code coordinate} - the CVE blast radius of a coordinate,
     *  read from the sharded index in a single small-object fetch. */
    public List<String> dependents(String repository, String coordinate) throws IOException {
        return dependentsQuery(repository).dependents(coordinate);
    }

    /** The first page of the versions whose manifest declares a dependency on {@code dependency}, with the
     *  requirement each states and, when {@code version} is given, whether it admits that version - screened so a
     *  version since deleted or withheld is not named - and whether more follow, which the screen says rather than
     *  presenting the page as the whole answer. */
    public DeclaredPage declarations(String repository, String dependency, String version) throws IOException {
        DependentsQuery.DeclarationPage page = dependentsQuery(repository)
                .declarations(dependency, null, Declarations.MAX_PAGE);
        return new DeclaredPage(Declarations.disclosable(new StoreRepositoryInventory(scope(repository)),
                page.declarations(), version), page.nextCursor() != null);
    }

    /** When the declared tier last passed over every published version of a repository, or {@code null} when it has
     *  not yet - rendered as "not yet indexed", so an empty list is not read as "nothing declares it". */
    public Instant declarationsBuiltAt(String repository) throws IOException {
        return dependentsQuery(repository).declarationsBuiltAt().orElse(null);
    }

    /** One screened page of the declared tier and whether the index holds more past it. */
    public record DeclaredPage(List<Declarations.Row> declarations, boolean more) {
    }

    /** A bounded first page of the coordinates the reverse-dependency index holds a dependent for, sorted for the
     *  console picker; the complete enumeration is paged over {@code /api/dependents ?after=}. The page arrives in the
     *  index's shard-then-coordinate order, so it is sorted here for the picker. */
    public List<String> dependencyCoordinates(String repository) throws IOException {
        List<String> page = new ArrayList<>(
                dependentsQuery(repository).coordinates(null, DEPENDENCY_COORDINATE_PICKER_LIMIT).coordinates());
        Collections.sort(page);
        return page;
    }

    /** The largest coordinate set the console dependents picker materialises - a bounded convenience list, not the
     *  whole index (a client wanting the complete enumeration pages {@code /api/dependents}), so a very large
     *  reverse-dependency graph never lands whole in heap or in the rendered page. */
    private static final int DEPENDENCY_COORDINATE_PICKER_LIMIT = 1000;

    private DependentsQuery dependentsQuery(String repository) {
        return DependentsQueryProvider.installed()
                .orElseThrow(() -> new IllegalStateException(
                        "The reverse-dependency index is not installed on this deployment."))
                .over(scope(repository));
    }
}
