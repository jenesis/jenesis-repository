package build.jenesis.repository.management.web;

import module java.base;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.CredentialContext;
import build.jenesis.repository.server.spi.Authorization;

/**
 * This distribution's answers to the two questions the core's credential surface leaves open.
 *
 * <p>The routes themselves live once, in the core's {@code CredentialsController}, and act on the tenant the
 * routing answers for the request. What differs here is not logic: every mutation is written to the audit ledger,
 * against the tenant it acted on, and a caller naming no tenant falls back to the configured default rather than the
 * core's. Publishing this bean is what makes the core's default step aside.
 */
public final class AuditedCredentialContext implements CredentialContext {

    private final String defaultTenant;
    private final AuditTrail audit;

    public AuditedCredentialContext(String defaultTenant, AuditTrail audit) {
        this.defaultTenant = Objects.requireNonNull(defaultTenant, "defaultTenant");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** The configured default tenant, which is what the token exchange falls back to when a caller names
     *  none - the core answers its single tenant there, and a multi-tenant deployment answers what it was told. */
    @Override
    public String defaultTenant() {
        return defaultTenant;
    }

    @Override
    public void audit(String tenant, String key, String action, String detail) {
        audit.record(tenant, key == null ? "anonymous" : Authorization.hash(key), action, detail);
    }
}
