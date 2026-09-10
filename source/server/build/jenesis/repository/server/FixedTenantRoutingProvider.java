package build.jenesis.repository.server;

import module java.base;

/**
 * Installs {@link FixedTenantRouting} as {@code jenreg.tenancy=fixed}, and as the routing a deployment that names
 * none gets. Single tenancy is the default because it is the shape most deployments are and the only one the free
 * core ships: a deployment with one tenant and one repository needs nothing resolved per request.
 */
public final class FixedTenantRoutingProvider implements RepositoryRoutingProvider {

    @Override
    public String name() {
        return FIXED;
    }

    @Override
    public RepositoryRouting create(RoutingContext context) {
        return new FixedTenantRouting(context.root(), context.defaultTenant(), context.defaultRepository());
    }
}
