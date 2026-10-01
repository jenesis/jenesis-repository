package build.jenesis.repository.ui.store;

import module java.base;
import build.jenesis.repository.settings.StoredSettings;

import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.cleanup.StoredReport;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.server.Observations;
import build.jenesis.repository.store.ArtifactStore;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * The tenant-and-repository confinement the console's repository services share: the signed-in tenant, the
 * {@link ArtifactStore} scoped to {@code <tenant>/<repo>} behind the name guards, the deployment settings and the
 * timing of an admin action.
 */
public abstract class TenantScope {

    /** The tenant a pass handed off the request thread runs for: the request's own, bound around the pass. */
    private static final ScopedValue<String> HANDED_OFF = ScopedValue.newInstance();

    protected final ArtifactStore root;
    protected final CurrentTenant current;
    protected final ObservationRegistry observations;
    protected final AuditTrail audit;
    protected final ConsoleActor actor;

    /** A read-only service that records no audit events. */
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
     * Records a privileged mutation under the signed-in tenant, attributed to the member; best-effort. The action is an
     * {@link build.jenesis.repository.audit.AuditActions} value, shared with the API.
     */
    protected final void audit(String action, String target) {
        audit.record(tenant(), actor.name(), action, target);
    }

    /** The {@link ArtifactStore} scoped to {@code <tenant>/<repo>}, refusing a reserved or traversal-carrying name. */
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

    /** The deployment's stored settings, read as the repository server's settings read them
     *  ({@link StoredSettings#read(ArtifactStore)}) - for a console decision that reads a module's configuration. */
    protected final Properties settings() throws IOException {
        Properties properties = new Properties();
        properties.putAll(StoredSettings.read(root));
        return properties;
    }

    /**
     * {@code pass} bound to the tenant of the request that starts it, for a pass on another thread, where no session
     * names one.
     */
    protected final StoredReport.Pass forThisTenant(StoredReport.Pass pass) {
        String tenant = tenant();
        return () -> ScopedValue.where(HANDED_OFF, tenant).call(pass::run);
    }

    /** Times and traces an admin action through {@link Observations}, tagged with the action, repository and tenant
     *  ({@code none} when no tenant is selected). */
    final <T> T observe(String action, String repository, AdminCall<T> call) throws IOException {
        return Observations.observe(observations, "jenrepo.ui.admin", repository, current.name(), observation -> {
            observation.lowCardinalityKeyValue("action", action);
            return call.call(observation);
        });
    }

    protected final String tenant() {
        String tenant = HANDED_OFF.isBound() ? HANDED_OFF.get() : current.name();
        if (tenant == null) {
            throw new IllegalStateException("No tenant selected.");
        }
        return tenant;
    }

    /** Whether {@code name} is a repository name by the shared {@link Scopes#valid} rule, so a reserved space beside the
     *  repositories is never listed or scoped into. */
    protected static boolean validRepository(String name) {
        return Scopes.valid(name);
    }

    interface AdminCall<T> {
        T call(Observation observation) throws IOException;
    }
}
