package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.scope.Scopes;

/**
 * What a deployment may vary about the credential surface, so that the surface itself exists only once.
 *
 * <p>{@link CredentialsController} owns every credential route - list, mint, grant, revoke, expiry, rotate and the
 * source-IP allowlist - and every one of them is a thin call onto {@link Authorization}, which has always held the
 * logic. The tenant a route acts on is the routing's ({@link RepositoryRouting#tenant}), as for every other
 * {@code /api} call. What a richer distribution does differently is <em>not</em> logic: whether a mutation is
 * recorded, and which tenant a caller naming none falls back to. Both are handed in here, so a distribution
 * overrides the answer rather than restating the routes.
 *
 * <p>Registered as a {@code @ConditionalOnMissingBean}, which is this codebase's established override: the
 * deployment that wants different behaviour publishes its own bean and the default steps aside. Nothing else about
 * the surface is overridable, deliberately - a second implementation of "issue a credential" would drift, and the
 * drift would be in an authorization surface.
 */
public interface CredentialContext {

    /**
     * Record a credential mutation, if this deployment records them: {@code tenant} is the tenant the mutation acted
     * on, {@code key} the credential that made it.
     *
     * <p>A no-op by default rather than a required dependency: the core has no audit ledger, and making one up so
     * that the surface could call it would be the duplication this seam exists to avoid. A distribution with a
     * ledger writes to it here, and gets the same routes.
     */
    default void audit(String tenant, String key, String action, String detail) {
    }

    /**
     * The tenant a surface acts on when a caller names none - the token exchange is the one that asks, because a CI
     * job posting its id-token has no key to derive a tenant from yet.
     *
     * <p>Answered here rather than read from configuration by the caller, because which property carries it is a
     * deployment's own business: this core has one tenant and a distribution with several has a configured default.
     */
    default String defaultTenant() {
        return Scopes.DEFAULT_TENANT;
    }

    /** The default: no auditing. */
    static CredentialContext basic() {
        return new CredentialContext() {
        };
    }
}
