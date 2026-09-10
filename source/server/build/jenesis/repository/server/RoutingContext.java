package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;

/**
 * What a {@link RepositoryRoutingProvider} is handed to build its routing with: the deployment's store, its
 * configuration, and the two questions a routing has to ask about a repository it did not choose.
 *
 * <p><strong>Why this exists rather than the routings taking their collaborators directly.</strong> A routing is
 * selected at boot, and the implementations were reached by naming their constructors in a Spring configuration -
 * which made tenancy a composition choice rather than an extension point, so a new routing could only be added by
 * editing that configuration. Discovering them needs one thing they can all be built from, and the obstacle
 * looked like the enterprise {@code Repositories} type all three multi-tenant routings take. It is not: between
 * them they call three of its methods, and the one returning an enterprise type is consulted only for a boolean.
 * So the whole dependency fits in a seam the free core can declare, and tenancy becomes discovered like every
 * other extension point.
 *
 * <p>A provider reads whatever else it needs from {@link #config}, which is the deployment's namespaced settings -
 * the same accessor an {@link build.jenesis.repository.store.ArtifactStoreProvider} takes. That keeps a routing's
 * own dials its own business: the host mapping the host routing reads is not a clause on this interface, because
 * a seam that named every implementation's settings would have to grow one per implementation.
 */
public interface RoutingContext {

    /** The deployment's unscoped store. A routing scopes it {@code root.scope(tenant).scope(repository)}. */
    ArtifactStore root();

    /** The deployment's settings, namespaced as every other provider reads them; {@code null} when unset. */
    String config(String key);

    /** The tenant a request resolves to when nothing names one. */
    String defaultTenant();

    /** The repository a request resolves to when nothing names one. */
    String defaultRepository();

    /**
     * The tenant a credential names, for a routing that takes the tenant from the key rather than from the URI.
     *
     * <p>Answers {@link #defaultTenant()} for a {@code null} or unreadable key, never an exception: resolving a
     * route is not the place a bad credential is refused - authorization is - and a routing that threw here would
     * turn a malformed header into a failure to route rather than a failure to authorize.
     */
    String tenantOf(String key);

    /**
     * The doubly-scoped store for one tenant's repository, with the routing's writability decision already made -
     * the seam that keeps a caller from scoping a store by hand and publishing into a proxy, a group view or a
     * read-only repository that a request would have been refused from.
     */
    ArtifactStore store(String tenant, String repository) throws IOException;

    /**
     * Whether a repository accepts writes. {@code true} for a repository this deployment holds no definition for,
     * which is the hosted default; {@code false} only when something says otherwise (a proxy, a group view, one
     * marked read-only). Deliberately a boolean rather than the definition itself: that is all any routing asks,
     * and the definition is an enterprise type this seam must not name.
     */
    boolean writable(String repository);
}
