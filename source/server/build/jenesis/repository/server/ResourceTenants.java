package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.blobs.RequestBase;
import build.jenesis.repository.scope.Scopes;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The tenant a {@code resource} of a token-exchange request names (RFC 8693 section 2.1: the URI of the target
 * service where the client intends to use the token). A resource is honoured when it is an absolute {@code http} or
 * {@code https} URI without a fragment, lies under the address clients reach this deployment at
 * ({@link RequestBase}: the operator's {@code public-url}, a trusted proxy's forwarded host, else the request's own
 * {@code Host}), and one of the deployment's {@link TenantResource}s reads a tenant from its path. Any other names
 * no tenant, which the endpoint answers with {@code invalid_target}.
 */
public final class ResourceTenants {

    private final List<TenantResource> resources;
    private final UnaryOperator<String> settings;

    /**
     * @param resources the URL spaces of the deployment that name a tenant.
     * @param settings  the bare setting key to its effective value, {@code null} when unset.
     */
    public ResourceTenants(List<TenantResource> resources, UnaryOperator<String> settings) {
        this.resources = List.copyOf(resources);
        this.settings = settings;
    }

    /** The tenant {@code resource} names for a client making {@code request}, or empty when it names none. */
    public Optional<String> tenant(HttpServletRequest request, String resource) {
        URI named, base;
        try {
            named = new URI(resource);
            base = new URI(RequestBase.of(new ServletFormatExchange(request, null, "", settings)));
        } catch (URISyntaxException malformed) {
            return Optional.empty();
        }
        if (!named.isAbsolute() || !base.isAbsolute() || named.getRawFragment() != null
                || named.getHost() == null || base.getHost() == null
                || !named.getScheme().equalsIgnoreCase(base.getScheme())
                || !named.getHost().equalsIgnoreCase(base.getHost())
                || port(named) != port(base) || port(named) < 0) {
            return Optional.empty();
        }
        String path = named.normalize().getPath(), prefix = base.getPath() == null ? "" : base.getPath();
        if (!path.startsWith(prefix + "/")) {
            return Optional.empty();
        }
        String within = path.substring(prefix.length());
        for (TenantResource candidate : resources) {
            Optional<String> tenant = candidate.tenant(request, within);
            if (tenant.isPresent()) {
                return tenant.filter(Scopes::valid);
            }
        }
        return Optional.empty();
    }

    /** The port a URI is reached at: its own, else its scheme's; negative for a scheme that is not HTTP. */
    private static int port(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return uri.getScheme().equalsIgnoreCase("https") ? 443 : uri.getScheme().equalsIgnoreCase("http") ? 80 : -1;
    }
}
