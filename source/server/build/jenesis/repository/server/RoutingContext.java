package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.RepositoryDocument;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * What a {@link RepositoryRoutingProvider} is handed to build its routing with: the deployment's store, its
 * configuration, and the two questions a routing has to ask about a repository it did not choose.
 *
 * <p><strong>Why this exists rather than the routings taking their collaborators directly.</strong> A routing is
 * selected at boot, and the implementations were reached by naming their constructors in a Spring configuration -
 * which made tenancy a composition choice rather than an extension point, so a new routing could only be added by
 * editing that configuration. Discovering them needs one thing they can all be built from, and the obstacle
 * looked like the {@code Repositories} type all three multi-tenant routings take. It is not: between them they
 * call three of its methods, and the one returning a routing type is consulted only for a boolean.
 * So the whole dependency fits in a seam this module can declare, and tenancy becomes discovered like every
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
     * and a repository definition is a type contributed by whatever supplies the routing, which this seam must
     * not name.
     */
    boolean writable(String repository);

    /** The document of {@code tenant}'s {@code repository}, read through the node's cache over {@link #root()}. */
    default Optional<RepositoryDocument> document(String tenant, String repository) throws IOException {
        return RepositoryDocument.cached(root(), tenant, repository);
    }

    /**
     * The route for a request a routing has confined to {@code tenant}: the tenant it answers for, whatever else the
     * URL names. A URL naming another tenant is a {@code 404}, which says no more about that tenant than an absent
     * repository would; a URL naming none - the OCI registry's version probe - routes to {@code tenant}.
     */
    default RepositoryRouting.Route confined(String tenant, RepositoryRouting.Target target) {
        if (!target.tenant().isEmpty() && !target.tenant().equals(tenant)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such tenant");
        }
        return route(tenant, target);
    }

    /**
     * The route for a tenant a routing has decided and the {@link RepositoryRouting.Target target} its URL names -
     * the one resolution every routing shares, so they differ only in which tenants they answer. Both names are
     * checked as scope names before they scope the store, so a traversal is a {@code 400} and never an escape; an
     * empty repository (the OCI registry's version probe) routes to the tenant's own scope.
     */
    default RepositoryRouting.Route route(String tenant, RepositoryRouting.Target target) {
        if (!Scopes.valid(tenant)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a routable tenant name");
        }
        String repository = target.repository();
        if (repository.isEmpty()) {
            return new RepositoryRouting.Route(tenant, "", root().scope(tenant), target.path());
        }
        if (!Scopes.valid(repository)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a routable repository name");
        }
        try {
            return new RepositoryRouting.Route(tenant, repository, store(tenant, repository), target.path(),
                    writable(repository));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
