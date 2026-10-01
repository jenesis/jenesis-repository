package build.jenesis.repository.management.web;

import module java.base;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.CredentialContext;
import build.jenesis.repository.server.spi.Authorization;

/**
 * This distribution's answers to the two questions the core's credential surface leaves open. The routes live once, in
 * the core's {@code CredentialsController}, acting on the tenant the routing answers; here every mutation is written to
 * the audit ledger against that tenant, and a caller naming no tenant falls back to the configured default. Publishing
 * this bean makes the core's default step aside.
 */
public final class AuditedCredentialContext implements CredentialContext {

    private final String defaultTenant;
    private final AuditTrail audit;

    public AuditedCredentialContext(String defaultTenant, AuditTrail audit) {
        this.defaultTenant = Objects.requireNonNull(defaultTenant, "defaultTenant");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** The configured default tenant, which the token exchange falls back to when a caller names none. */
    @Override
    public String defaultTenant() {
        return defaultTenant;
    }

    @Override
    public void audit(String tenant, String key, String action, String detail) {
        audit.record(tenant, key == null ? "anonymous" : Authorization.hash(key), action, detail);
    }
}
