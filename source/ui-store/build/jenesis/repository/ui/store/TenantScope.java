package build.jenesis.repository.ui.store;

import module java.base;

import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.server.Observations;
import build.jenesis.repository.store.ArtifactStore;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * The tenant-and-repository confinement the console's repository services share: it names the signed-in tenant, scopes
 * the artifact {@link ArtifactStore} to {@code <tenant>/<repo>} (never a reserved sibling key-space), reads the
 * deployment settings and times an admin action, so each sibling service ({@link RepositoryAdmin}, {@link
 * RepositoryBrowse}, {@code ComplianceReview}, {@link RepositoryImports}, {@link RepositoryLifecycle}, {@link
 * TenantLimits}) works on one repository without re-deriving the scoping or forking the {@code
 * validRepository}/traversal guards.
 */
public abstract class TenantScope {

    protected final ArtifactStore root;
    protected final CurrentTenant current;
    protected final ObservationRegistry observations;
    protected final AuditTrail audit;
    protected final ConsoleActor actor;

    /** A read-only console service that records no audit events: the audit seam stands in as the no-op trail and a
     *  neutral actor, so a browse/listing service need not carry collaborators it never uses. A mutating service uses
     *  the five-argument constructor below and calls {@link #audit(String, String)}. */
    protected TenantScope(ArtifactStore root, CurrentTenant current, ObservationRegistry observations) {
        this(root, current, observations, AuditTrail.none(), () -> "console");
    }

    protected TenantScope(ArtifactStore root, CurrentTenant current, ObservationRegistry observations,
                          AuditTrail audit, ConsoleActor actor) {
        this.root = root;
        this.current = current;
        this.observations = observations;
        this.audit = audit;
        this.actor = actor;
    }

    /**
     * Record a privileged console mutation on the shared audit trail, attributing it to the acting member and the
     * signed-in tenant - the same seam {@link CredentialService} uses. Best-effort by the trail's contract: a
     * failed write never fails the mutation it audits.
     *
     * <p>The console and its {@code /api} twin audit a privileged mutation under the same {@code action}
     * (§9). This javadoc used to simply assert that, which is worth exactly what any restated rule is
     * worth: the two names were separate literals in separate modules and nothing would have failed on the day
     * they diverged. They are now single values on
     * {@link build.jenesis.repository.audit.AuditActions}, and the build fails a module that spells one out for
     * itself, so the property is held by the code rather than by this sentence.
     */
    protected final void audit(String action, String target) {
        audit.record(tenant(), actor.name(), action, target);
    }

    /** The repository {@link ArtifactStore} scoped to {@code <tenant>/<repo>} - the confinement every per-repository op
     *  works within, refusing a reserved sibling key-space or a traversal-carrying name up front. */
    protected final ArtifactStore scope(String repository) {
        if (!validRepository(repository)) {
            throw new IllegalArgumentException("Invalid repository name: " + repository);
        }
        return root.scope(tenant()).scope(repository);
    }

    /** The inventory over a repository's scoped store, for the release/coordinate/pin reads the console renders. */
    protected final StoreRepositoryInventory inventory(String repository) {
        return new StoreRepositoryInventory(scope(repository));
    }

    /** The deployment-wide runtime settings, the same store of truth the settings screens edit and the repository
     *  server reads. */
    protected final Properties settings() throws IOException {
        return StoredConfig.load(root);
    }

    /** Time and trace a console admin action through the canonical {@link Observations} wrapper, tagging it with the
     *  low-cardinality {@code action} plus the repository and tenant, so the one instrumentation point feeds metrics,
     *  logging and tracing together. The tenant is read null-tolerantly (the wrapper records {@code none} when no
     *  tenant is selected) rather than through the throwing {@link #tenant()} accessor the store scoping uses. */
    final <T> T observe(String action, String repository, AdminCall<T> call) throws IOException {
        return Observations.observe(observations, "jenreg.ui.admin", repository, current.name(), observation -> {
            observation.lowCardinalityKeyValue("action", action);
            return call.call(observation);
        });
    }

    protected final String tenant() {
        String tenant = current.name();
        if (tenant == null) {
            throw new IllegalStateException("No tenant selected.");
        }
        return tenant;
    }

    /** Whether {@code name} is a usable repository name: the shared {@link Scopes#valid} rule - a traversal-free
     *  segment that is not a reserved store namespace - which is the same predicate the server's
     *  {@code Repositories.valid} applies on the routing/publish path and the same one every tenant and repository
     *  enumeration filters by. Once a tenant saves a setting ({@code <tenant>/config/...}) or a quota is written
     *  ({@code <tenant>/quota/used}), those siblings must never surface as phantom repositories the console lists, nor
     *  let an admin op (retention, cleanup, import, pin) scope into a reserved namespace. */
    protected static boolean validRepository(String name) {
        return Scopes.valid(name);
    }

    interface AdminCall<T> {
        T call(Observation observation) throws IOException;
    }
}
