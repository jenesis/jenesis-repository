package build.jenesis.repository.server;

import module java.base;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.store.RepositoryDocument;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The fixed-tenant {@link RepositoryRouting}: the deployment answers one tenant, {@code jenreg.default-tenant}, and a
 * URL naming any other is a {@code 404}. The URL names the tenant all the same ({@link RepositoryRouting#target}),
 * exactly as under a multi-tenant routing, so switching a deployment to one is a configuration change that moves no
 * URL a client has, over a layout where the data is found where it was left.
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
        return context.confined(tenant, RepositoryRouting.target(request.getRequestURI()));
    }

    @Override
    public String tenant(HttpServletRequest request) {
        return tenant;
    }

    @Override
    public Optional<Route> route(String tenant, String repository, String path) {
        return this.tenant.equals(tenant) && Scopes.valid(repository)
                ? Optional.of(context.route(tenant, new Target(tenant, repository, path)))
                : Optional.empty();
    }

    @Override
    public Optional<RepositoryDocument> document(Route route) throws IOException {
        return route.repository().isEmpty() ? Optional.empty() : context.document(route.tenant(), route.repository());
    }
}
