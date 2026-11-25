package build.jenesis.repository.server;

import module java.base;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * The repositories' URLs as a {@link TenantResource}: the tenant of a URL the {@link RepositoryRouting} addresses is
 * the one the deployment's routing routes it to, asked of the routing itself with a view of the request that answers
 * the URL's path. So the fixed routing answers its one tenant and refuses a URL naming another, and a routing that
 * lets the URL choose answers the URL's.
 */
public final class RoutedTenantResource implements TenantResource {

    private final RepositoryRouting routing;

    public RoutedTenantResource(RepositoryRouting routing) {
        this.routing = routing;
    }

    @Override
    public Optional<String> tenant(HttpServletRequest request, String path) {
        if (!RepositoryRouting.addresses(path)) {
            return Optional.empty();
        }
        return routing.resolve(new HttpServletRequestWrapper(request) {
            @Override
            public String getRequestURI() {
                return path;
            }

            @Override
            public String getServletPath() {
                return path;
            }

            @Override
            public String getPathInfo() {
                return null;
            }

            @Override
            public String getQueryString() {
                return null;
            }
        }).map(RepositoryRouting.Route::tenant);
    }
}
