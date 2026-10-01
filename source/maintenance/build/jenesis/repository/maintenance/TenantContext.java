package build.jenesis.repository.maintenance;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * What a {@link MaintenanceTask} sees in its per-tenant hook, after all of the tenant's repositories were visited: the
 * tenant's quota and the recount that reconciles the usage counter against what survived the pass (a sweep deletes
 * through the un-metered store), the configuration lookup resolved for this tenant, and the tenant's root store.
 */
public interface TenantContext {

    String tenant();

    /** The store scoped to this tenant's whole subspace, un-metered, including the dot-spaces ({@code .scans},
     *  {@code .tests}) the per-repository hook never visits. */
    ArtifactStore store();

    /** The deployment's product space ({@code .system}), un-metered, where per-tenant product data such as a tenant's
     *  audit trail lives. */
    ArtifactStore system();

    /** The effective configuration lookup for {@link #tenant()}, {@code null} for an unset key, resolved exactly as
     *  {@link RepositoryContext#config()} does. Read per pass, so a changed setting applies on the next pass. */
    UnaryOperator<String> config();

    /** The tenant's storage quota in bytes, or {@code 0} when unlimited. */
    long quotaLimit() throws IOException;

    /** Recount stored content and persist it as the authoritative usage; returns the total. */
    long recomputeQuota() throws IOException;

    /** The pass timestamp, stable for the whole pass. */
    Instant now();
}
