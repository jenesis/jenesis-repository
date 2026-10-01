package build.jenesis.repository.posture;

/**
 * Who a {@link SecurityAdvisory} concerns: a {@link #DEPLOYMENT}-wide advisory is the operator's to fix; a
 * {@link #TENANT}-scoped one belongs to that tenant's admins and carries the tenant. Deployment-wide is the default, so
 * a core seed, which reads only deployment configuration, is never shown to a tenant admin who cannot act on it.
 */
public enum Scope {

    /** Concerns the whole deployment - the operator's to fix. */
    DEPLOYMENT,
    /** Concerns a single tenant - that tenant's admins' to fix. */
    TENANT
}
