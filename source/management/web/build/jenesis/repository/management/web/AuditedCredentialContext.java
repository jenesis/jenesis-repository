package build.jenesis.repository.management.web;

import module java.base;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.CredentialContext;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.spi.Authorization;

/**
 * This distribution's answers to the two questions the core's credential surface leaves open.
 *
 * <p>The routes themselves live once, in the core's {@code CredentialsController}. What differs here is not logic:
 * the tenant is resolved through the deployment's own tenancy rather than read off the key, and every mutation is
 * written to the audit ledger. Publishing this bean is what makes the core's default step aside.
 */
public final class AuditedCredentialContext implements CredentialContext {

    private final Repositories repositories;
    private final AuditTrail audit;

    public AuditedCredentialContext(Repositories repositories, AuditTrail audit) {
        this.repositories = Objects.requireNonNull(repositories, "repositories");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @Override
    public String tenant(String key) {
        return repositories.tenant(key);
    }

    /** The configured default tenant, which is what the token exchange falls back to when a caller names
     *  none - the core answers its single tenant there, and a multi-tenant deployment answers what it was told. */
    @Override
    public String defaultTenant() {
        return repositories.tenant(null);
    }

    @Override
    public void audit(String key, String action, String detail) {
        audit.record(repositories.tenant(key), key == null ? "anonymous" : Authorization.hash(key),
                action, detail);
    }
}
