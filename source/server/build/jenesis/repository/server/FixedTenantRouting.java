package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The fixed-tenant {@link RepositoryRouting}: every request addresses the one configured artifact space -
 * {@code jenreg.tenant} / {@code jenreg.repository}, each {@code default} by default - so
 * the route always carries the doubly-scoped {@code root.scope(tenant).scope(repository)} store, the same
 * {@code <tenant>/<repository>/...} layout a multi-tenant routing addresses. Artifacts are served under the
 * {@code /repository/} prefix, which is stripped so a format sees its own {@code /maven/}, {@code /raw/} ... path;
 * the OCI {@code /v2/} registry, which the Docker protocol pins at the host root, is offered unchanged. A
 * multi-tenant edition installs a {@link RepositoryRoutingProvider} of its own and a deployment names it with
 * {@code jenreg.tenancy}; either switch is a configuration change over the same layout, so the data is found
 * where it was left.
 */
public final class FixedTenantRouting implements RepositoryRouting {

    private static final String PREFIX = "/repository";

    private final String tenant;
    private final String repository;
    private final ArtifactStore store;

    public FixedTenantRouting(ArtifactStore root, String tenant, String repository) {
        this.tenant = tenant;
        this.repository = repository;
        store = root.scope(tenant).scope(repository);
    }

    @Override
    public Route route(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String path = uri.equals(PREFIX) || uri.startsWith(PREFIX + "/") ? uri.substring(PREFIX.length()) : uri;
        return new Route(tenant, repository, store, path.isEmpty() ? "/" : path);
    }

    /**
     * The one configured space, when the caller names it and nothing else. A deployment with one tenant and one
     * repository can answer this without a request - there is nothing to resolve - so an in-process publish is
     * routed here exactly as a request would be, and refused when it names anything else rather than silently
     * publishing into the configured space under another name.
     */
    @Override
    public Optional<Route> route(String tenant, String repository, String path) {
        return this.tenant.equals(tenant) && this.repository.equals(repository)
                ? Optional.of(new Route(tenant, repository, store, path))
                : Optional.empty();
    }

    /**
     * Only the configured repository, because this routing has no URL that names another one: the request path
     * carries a format, never a repository. That is a statement about URLs and not about usefulness - a definition
     * under another name is still reached when something else names it, a group member or a fallback - so what it
     * lets a surface do is warn an operator that the name will not answer, never refuse to store it.
     */
    @Override
    public boolean addresses(String repository) {
        return this.repository.equals(repository);
    }
}
