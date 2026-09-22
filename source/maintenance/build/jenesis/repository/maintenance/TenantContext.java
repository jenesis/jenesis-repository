package build.jenesis.repository.maintenance;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * What a {@link MaintenanceTask} sees in its per-tenant hook, after all of the tenant's repositories were visited:
 * the tenant's storage quota and the recount that reconciles the usage counter against what actually survived the
 * pass (a sweep deletes through the un-metered store, so the counter drifts until reconciled), the effective
 * configuration lookup resolved for this tenant, and the tenant's root store for the tenant-wide key spaces that
 * live beside the repositories (the reserved dot-spaces).
 *
 * <p>It is deliberately the same lookup {@link RepositoryContext#config()} answers, resolved for the same tenant: the
 * two hooks differ in <em>which store</em> they sweep, never in which settings they can see. Until this
 * interface carried no lookup at all, so a tenant-hook pass had to read its dials from whatever the <em>provider</em>
 * captured at construction - deployment-global - and a tenant override silently no-opped for every per-tenant sweep
 * while its per-repository siblings honoured one. That is backwards for a product whose storage model is per-tenant
 * by default.
 */
public interface TenantContext {

    String tenant();

    /** The store scoped to this tenant's whole subspace, un-metered - the repositories and the reserved dot-spaces
     *  ({@code .scans}, {@code .tests}) sit under it, so a tenant-wide pass (a telemetry reap) reaches the spaces the
     *  per-repository hook never visits. */
    ArtifactStore store();

    /** The effective configuration lookup for {@link #tenant()}, {@code null} for an unset key - an operator's pin
     *  over this tenant's override over the deployment-wide override over the file/env default, exactly as
     *  {@link RepositoryContext#config()} resolves it. A global-only key resolves deployment-wide through it, so a
     *  pass reading a deployment knob sees no difference; a tenant-overridable one (a retention age over a tenant's
     *  own {@code .tests} or {@code .scans} space) resolves to what that tenant asked for. Read per pass rather than
     *  captured at construction, so a changed setting applies on the next pass without a restart. */
    UnaryOperator<String> config();

    /** The tenant's storage quota in bytes, or {@code 0} when unlimited. */
    long quotaLimit() throws IOException;

    /** Recount stored content and persist it as the authoritative usage; returns the total. */
    long recomputeQuota() throws IOException;

    /** The pass timestamp, stable for the whole pass. */
    Instant now();
}
