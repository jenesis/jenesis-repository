package build.jenesis.repository.server;

import module java.base;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Whether the caller of a request may read another path, asked of the very authorization that decides every request
 * - the deployment's {@code repositoryAuthorizationManager}, whichever policy is installed - about a {@code GET} of
 * that path carrying the caller's own credential and address. So a read a format makes on the caller's behalf is
 * permitted exactly where the caller could have made it, and a policy that plugs in decides this too.
 *
 * <p>The question is asked of a view of the request that answers the other path and the read method and keeps every
 * attribute the decision writes to itself, so the decision recorded for the request being served is not disturbed.
 */
public final class AuthorizedReads implements RepositoryController.Reads {

    private final Supplier<AuthorizationManager<RequestAuthorizationContext>> manager;

    /** Over the deployment's request authorization, looked up when first asked; {@code null} from it permits nothing. */
    public AuthorizedReads(Supplier<AuthorizationManager<RequestAuthorizationContext>> manager) {
        this.manager = manager;
    }

    @Override
    public boolean permits(HttpServletRequest request, String path) {
        AuthorizationManager<RequestAuthorizationContext> decides = manager.get();
        if (decides == null) {
            return false;
        }
        AuthorizationResult result = decides.authorize(() -> SecurityContextHolder.getContext().getAuthentication(),
                new RequestAuthorizationContext(new ReadOf(request, path)));
        return result != null && result.isGranted();
    }

    /** The request as a {@code GET} of {@code path}, with attributes of its own. */
    private static final class ReadOf extends HttpServletRequestWrapper {

        private final String path;
        private final Map<String, Object> attributes = new HashMap<>();

        private ReadOf(HttpServletRequest request, String path) {
            super(request);
            this.path = path;
        }

        @Override
        public String getMethod() {
            return "GET";
        }

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

        @Override
        public StringBuffer getRequestURL() {
            return new StringBuffer(getScheme() + "://" + getServerName() + ":" + getServerPort() + path);
        }

        @Override
        public Object getAttribute(String name) {
            return attributes.containsKey(name) ? attributes.get(name) : super.getAttribute(name);
        }

        @Override
        public void setAttribute(String name, Object value) {
            attributes.put(name, value);
        }

        @Override
        public void removeAttribute(String name) {
            attributes.put(name, null);
        }
    }
}
