package build.jenesis.repository.server;

import module java.base;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.store.RepositoryDocument;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The fixed-tenant {@link RepositoryRouting}: every request addresses the one configured tenant,
 * {@code jenreg.default-tenant}, and names its repository in the URL ({@link RepositoryRouting#target}) exactly as a
 * multi-tenant routing does, so the two differ only in where the tenant comes from. Switching a deployment to a
 * multi-tenant routing is therefore a configuration change over the same layout, and the data is found where it was
 * left.
 */
public final class FixedTenantRouting implements RepositoryRouting {

    private final RoutingContext context;
    private final String tenant;

    public FixedTenantRouting(RoutingContext context, String tenant) {
        this.context = context;
        this.tenant = tenant;
    }

    @Override
    public Route route(HttpServletRequest request) {
        return context.route(tenant, RepositoryRouting.target(request.getRequestURI()));
    }

    @Override
    public Optional<Route> route(String tenant, String repository, String path) {
        return this.tenant.equals(tenant) && Scopes.valid(repository)
                ? Optional.of(context.route(tenant, new Target(repository, path)))
                : Optional.empty();
    }

    @Override
    public Optional<RepositoryDocument> document(Route route) throws IOException {
        return route.repository().isEmpty() ? Optional.empty() : context.document(route.tenant(), route.repository());
    }
}
