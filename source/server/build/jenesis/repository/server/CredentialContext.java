package build.jenesis.repository.server;

import build.jenesis.repository.server.spi.Authorization;

import module java.base;

/**
 * What a deployment may vary about the credential surface, so that the surface itself exists only once.
 *
 * <p>{@link CredentialsController} owns every credential route - list, mint, grant, revoke, expiry, rotate and the
 * source-IP allowlist - and every one of them is a thin call onto {@link Authorization}, which has always held the
 * logic. Two things a richer distribution does differently are <em>not</em> logic: where the acting tenant comes
 * from, and whether a mutation is recorded. Both are handed in here, so a distribution overrides the answer rather
 * than restating the routes.
 *
 * <p>Registered as a {@code @ConditionalOnMissingBean}, which is this codebase's established override: the
 * deployment that wants different behaviour publishes its own bean and the default steps aside. Nothing else about
 * the surface is overridable, deliberately - a second implementation of "issue a credential" would drift, and the
 * drift would be in an authorization surface.
 */
public interface CredentialContext {

    /**
     * The tenant whose credentials a managing key administers.
     *
     * <p>The default reads it out of the key itself, which is where a key's tenant already lives
     * ({@code jenk_<tenant>.<secret><checksum>}), so a single-tenant deployment needs no routing at all. A routed
     * deployment resolves it through its own tenancy instead.
     */
    default String tenant(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("A managing key is required to address a tenant's credentials");
        }
        String tenant = Authorization.tenantOf(key);
        if (tenant == null || tenant.isBlank()) {
            throw new IllegalArgumentException("The managing key names no tenant");
        }
        return tenant;
    }

    /**
     * Record a credential mutation, if this deployment records them.
     *
     * <p>A no-op by default rather than a required dependency: the core has no audit ledger, and making one up so
     * that the surface could call it would be the duplication this seam exists to avoid. A distribution with a
     * ledger writes to it here, and gets the same routes.
     */
    default void audit(String key, String action, String detail) {
    }

    /** The default: tenant from the key, no auditing. */
    static CredentialContext basic() {
        return new CredentialContext() {
        };
    }
}
