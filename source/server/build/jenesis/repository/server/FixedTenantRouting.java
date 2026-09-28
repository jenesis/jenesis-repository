package build.jenesis.repository.server;

import module java.base;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.RepositoryDocument;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The fixed-tenant {@link RepositoryRouting}: the deployment answers one tenant, {@code jenrepo.default-tenant}, and a
 * URL naming any other is a {@code 404}. The URL names the tenant all the same ({@link RepositoryRouting#target}),
 * exactly as under a multi-tenant routing, so switching a deployment to one is a configuration change that moves no
 * URL a client has, over a layout where the data is found where it was left.
 *
 * <p>A key names the tenant it was minted for, and a key naming any tenant but the one served and the operator tenant is
 * refused with a {@code 403}, as a key-confined routing refuses one naming another tenant than the URL. Authorization
 * decides a key's rights in its own tenant's credential space and matches them to a repository by name, so without
 * this a key surviving from a multi-tenant past - minted for another tenant, granting a repository of the same name
 * there - would reach this tenant's repository of that name. The operator tenant's keys pass because the operator
 * administers the deployment, which here is this one tenant; a keyless request passes too.
 */
public final class FixedTenantRouting implements RepositoryRouting {

    private final RoutingContext context;
    private final String tenant;
    private final String operator;

    /** Serving {@code tenant}, administered by the keys of {@code operator} as well as its own. */
    public FixedTenantRouting(RoutingContext context, String tenant, String operator) {
        this.context = context;
        this.tenant = tenant;
        this.operator = operator;
    }

    @Override
    public Route route(HttpServletRequest request) {
        confine(request);
        return context.confined(tenant, RepositoryRouting.target(request.getRequestURI()));
    }

    @Override
    public String tenant(HttpServletRequest request) {
        confine(request);
        return tenant;
    }

    /** Refuse a request whose key names neither the tenant served nor the operator tenant. */
    private void confine(HttpServletRequest request) {
        String named = Authorization.tenantOf(PresentedKey.from(request));
        if (named != null && !named.equals(tenant) && !named.equals(operator)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The credential's tenant is not the tenant this deployment serves");
        }
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
