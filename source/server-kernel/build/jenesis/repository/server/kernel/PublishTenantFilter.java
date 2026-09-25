package build.jenesis.repository.server.kernel;

import module java.base;

import build.jenesis.repository.server.RepositoryRouting;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the request's tenant to the publishing thread for the artifact write/serve surfaces, so the discovered
 * compliance gate resolves that tenant's own policy without the publication-interceptor chain carrying a
 * tenant name. This is the tenant-resolution concern the retired {@code DeployController} used to open inline around its
 * {@code Publication.screen} call: the fork bound {@link PublishTenant#open the scope} itself, but with writes
 * now flowing through {@code RepositoryController} the binding must be opened <em>around</em> that controller -
 * so it moves to this servlet filter, which wraps the whole dispatch on the one request thread the screening and the
 * publish run on.
 *
 * <p>The filter matches the surfaces a write can land on: {@code /repository/**}, the host-rooted OCI registry
 * {@code /v2/**} (whose manifest choke point publishes too), a staged upload under {@code /staging/**}, and a
 * repository's operations under {@code /api/repository/**}, where an import and a staged release's promotion publish
 * through the gate. An operation names no tenant in its URL, so it binds the tenant the routing answers for a request
 * that addresses none. The tenant is resolved through the
 * active {@link RepositoryRouting} - exactly the tenant that scopes the request's store - not the
 * {@code Jenesis-Repository-Key} header alone. That distinction is load-bearing under path- and host-tenancy: there the
 * tenant rides in the URL path or the request Host, not the key, so a keyless write to {@code /repository/acme/...}
 * must be screened with {@code acme}'s own gate policy, not the default tenant's. Under multi- and fixed-tenancy the
 * routing derives the tenant from the key (or the fixed default) just as before, so the binding is unchanged there. The
 * scope restores the previous binding on close (a reused request thread never leaks a tenant into the next request), and
 * other paths pass straight through unbound.
 */
public final class PublishTenantFilter extends OncePerRequestFilter {

    private final RepositoryRouting routing;

    public PublishTenantFilter(RepositoryRouting routing) {
        this.routing = routing;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!binds(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        // Bind the tenant the ACTIVE routing resolves for this request - the same tenant that scopes the store - so the
        // free edge's screen and the DeployEdgeHooks bean resolve that tenant's own gate policy. Under path/host tenancy
        // the tenant comes from the path/host (a keyless CDN write names a non-default tenant), which the key header
        // alone could not see; the key-must-agree precedence in those routings still confines a keyed request.
        // A routing refusal is the controller's to answer: leave the tenant unbound and let the request proceed.
        Optional<String> tenant;
        if (request.getRequestURI().startsWith(RepositoryRouting.OPERATIONS)) {
            try {
                tenant = Optional.of(routing.tenant(request));
            } catch (RuntimeException refused) {
                tenant = Optional.empty();
            }
        } else {
            tenant = routing.resolve(request).map(RepositoryRouting.Route::tenant);
        }
        if (tenant.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        try (PublishTenant.Scope _ = PublishTenant.open(tenant.get())) {
            chain.doFilter(request, response);
        }
    }

    /** The surfaces a write can reach: the {@code /repository} tree, the host-rooted OCI {@code /v2} registry, staged
     *  uploads and a repository's operations. Everything else (the console, the other {@code /api} management
     *  surfaces) publishes no artifact and needs no tenant bound. */
    private static boolean binds(String uri) {
        return uri.equals("/repository") || uri.startsWith("/repository/")
                || uri.equals("/v2") || uri.startsWith("/v2/")
                || uri.startsWith(RepositoryRouting.STAGING) || uri.startsWith(RepositoryRouting.OPERATIONS);
    }
}
