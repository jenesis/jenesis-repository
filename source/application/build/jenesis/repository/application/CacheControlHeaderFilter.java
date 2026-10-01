package build.jenesis.repository.application;

import module java.base;
import build.jenesis.repository.gateway.HardenedScreen;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives artifact {@code GET}/{@code HEAD} responses an immutability-driven {@code Cache-Control}, so a CDN or proxy in
 * front of the serve plane can cache them.
 *
 * <p>Spring Security's default header writer stamps {@code no-store} on every response, but only when the response
 * carries no {@code Cache-Control} yet, and only at commit. This filter runs after authorization, wraps the response
 * the format writes to and sets the header at the format's commit point, so the writer defers to it. Every route it
 * does not touch (API, console, auth, actuator) keeps {@code no-store}: the scoping is this filter's own
 * path/method/status guard, never a global disable of the writer.
 *
 * <p>For a {@code GET}/{@code HEAD} under {@code /repository/} answering {@code 200}, {@code 206} or {@code 304}:
 * <ul>
 *   <li>a released versioned artifact file ({@link #immutableArtifact}) -&gt;
 *       {@code Cache-Control: public, max-age=31536000, immutable};</li>
 *   <li>anything else (a {@code -SNAPSHOT}, {@code maven-metadata.xml}, a directory index, a packument or dist-tag)
 *       -&gt; {@code Cache-Control: no-cache}.</li>
 * </ul>
 * Any other status keeps the default {@code no-store}.
 *
 * <p><b>An {@code ETag} outranks the path.</b> A name test cannot tell a frozen artifact from a generated index with
 * an extension ({@code repodata.json}, {@code Packages.gz}, {@code index.json}), and a year of {@code immutable} on an
 * index hides every later publish from the client. Generated indexes go out through the buffered path that sets an
 * {@code ETag} and streamed artifact bytes do not, so a response offering a validator gets {@code no-cache} whatever
 * its name. An artifact that happens to carry one only loses shared caching, the harmless direction.
 */
public final class CacheControlHeaderFilter extends OncePerRequestFilter {

    /** Artifact reads live under this prefix, {@code /repository/<tenant>/<repository>/...}. */
    private static final String ARTIFACT_PREFIX = "/repository/";

    static final String IMMUTABLE = "public, max-age=31536000, immutable";
    static final String REVALIDATE = "no-cache";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String policy = policyFor(request);
        if (policy == null) {
            chain.doFilter(request, response);
            return;
        }
        CachePolicyResponse wrapped = new CachePolicyResponse(response, policy);
        try {
            chain.doFilter(request, wrapped);
        } finally {
            // A buffered reply (a metadata 200, a 304) never opens the body, so the policy is applied here too.
            wrapped.applyCachePolicy();
        }
    }

    /** The {@code Cache-Control} for this request's cacheable success, or {@code null} when the filter leaves the
     *  response alone. */
    private static String policyFor(HttpServletRequest request) {
        String method = request.getMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return null;
        }
        String uri = request.getRequestURI();
        if (uri == null || !uri.startsWith(ARTIFACT_PREFIX)) {
            return null;
        }
        return immutableArtifact(uri) ? IMMUTABLE : REVALIDATE;
    }

    /**
     * Whether {@code path} names a released, frozen artifact file (a year-cacheable immutable coordinate) rather than a
     * mutable document: {@link HardenedScreen#immutableCoordinate(String)} decides the snapshot test, then
     * release-level {@code maven-metadata.*}, directory indexes, npm dist-tags and extensionless roots (a packument, a listing) stay
     * mutable. Errs to mutable when unsure.
     */
    static boolean immutableArtifact(String path) {
        if (!HardenedScreen.immutableCoordinate(path)) {
            return false;                                   // a -SNAPSHOT coordinate: republished under the same path
        }
        int slash = path.lastIndexOf('/');
        String file = slash < 0 ? path : path.substring(slash + 1);
        if (file.isEmpty()) {
            return false;                                   // a directory index / listing
        }
        if (file.startsWith("maven-metadata.")) {
            return false;                                   // release-level metadata: changes as versions publish
        }
        if (file.equals("dist-tags") || path.contains("/dist-tags")) {
            return false;                                   // npm dist-tags: mutable
        }
        return file.indexOf('.') >= 0;                      // a concrete versioned artifact file has an extension; an
                                                            // extensionless root (packument/index) is mutable
    }

    /** Whether a committed status is a cacheable read success, the only responses given the relaxed header. */
    private static boolean cacheable(int status) {
        return status == 200 || status == 206 || status == 304;
    }

    /**
     * Stamps {@code Cache-Control} once, at the format's commit point ({@code getOutputStream}/{@code getWriter}), by
     * which time the status is set, and only on a cacheable success.
     */
    private static final class CachePolicyResponse extends HttpServletResponseWrapper {

        private final String policy;
        private boolean applied;

        private CachePolicyResponse(HttpServletResponse response, String policy) {
            super(response);
            this.policy = policy;
        }

        private void applyCachePolicy() {
            if (applied || isCommitted()) {
                applied = true;
                return;
            }
            applied = true;
            if (cacheable(getStatus())) {
                // An ETag means the format expects revalidation, whatever the path predicate concluded.
                super.setHeader("Cache-Control", getHeader("ETag") == null ? policy : REVALIDATE);
            }
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            applyCachePolicy();
            return super.getOutputStream();
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            applyCachePolicy();
            return super.getWriter();
        }

        @Override
        public void flushBuffer() throws IOException {
            applyCachePolicy();
            super.flushBuffer();
        }
    }
}
