package build.jenesis.repository.dependents.web;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

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
 * What the declared-dependencies index can answer for the console, read through the tenant a call runs in.
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

    /** Whether the declared-dependencies index module is installed on this deployment (the console hides the panel
     *  when it is not). */
    public boolean dependentsAvailable() {
        return DependentsQueryProvider.installed().isPresent();
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

    /** When the index last passed over every published version of a repository, or {@code null} when it has
     *  not yet - rendered as "not yet indexed", so an empty list is not read as "nothing declares it". */
    public Instant declarationsBuiltAt(String repository) throws IOException {
        return dependentsQuery(repository).declarationsBuiltAt().orElse(null);
    }

    /** One screened page of the declared tier and whether the index holds more past it. */
    public record DeclaredPage(List<Declarations.Row> declarations, boolean more) {
    }

    private DependentsQuery dependentsQuery(String repository) {
        return DependentsQueryProvider.installed()
                .orElseThrow(() -> new IllegalStateException(
                        "The declared-dependencies index is not installed on this deployment."))
                .over(scope(repository));
    }
}
