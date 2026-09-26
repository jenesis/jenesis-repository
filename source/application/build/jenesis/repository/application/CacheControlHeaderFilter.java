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
 * The CDN-cache precondition: give artifact {@code GET}/{@code HEAD} responses an
 * immutability-driven {@code Cache-Control} so a CDN or proxy in front of the serve plane can actually cache them.
 *
 * <p><b>The problem this solves.</b> The security chain configures no {@code .headers()}, so Spring Security's
 * default {@code HeaderWriterFilter} blankets <em>every</em> response with
 * {@code Cache-Control: no-cache, no-store, max-age=0, must-revalidate} (plus {@code Pragma}/{@code Expires}) - which
 * forbids any shared-cache retention. This filter overrides that for artifact serve reads only, by the seam Spring's
 * own {@code CacheControlHeadersWriter} leaves open: that writer is <em>set-if-absent</em> (it returns early when the
 * response already carries a {@code Cache-Control}), and it runs at response commit. So this filter, run after the
 * authorization layer and wrapping the response the format writes to, sets {@code Cache-Control} at the format's
 * commit point - before the default writer runs - and the writer then defers to it. Every route this filter does not
 * touch (API, console, auth, actuator) keeps the default {@code no-store}: the scoping is the filter's own
 * path/method/status guard, not a global disable of the writer, so relaxed caching can never leak onto a non-artifact
 * route (proven in {@code CacheControlHeaderFilterTest}).
 *
 * <p><b>The policy.</b> For a {@code GET}/{@code HEAD} under {@code /repository/} that answers a cacheable success
 * ({@code 200}/{@code 206}/{@code 304}):
 * <ul>
 *   <li>a released, frozen coordinate ({@link HardenedScreen#immutableCoordinate(String)} true, and a concrete
 *       versioned artifact file rather than a metadata/index/packument/dist-tag document) -&gt;
 *       {@code Cache-Control: public, max-age=31536000, immutable};</li>
 *   <li>everything else mutable (a {@code -SNAPSHOT}, a {@code maven-metadata.xml}, a directory index, an npm packument
 *       or dist-tag) -&gt; {@code Cache-Control: no-cache} (revalidate). These already carry {@code ETag}s from the free
 *       {@code ServletFormatExchange} buffered-200 path, which this filter leaves untouched.</li>
 * </ul>
 * A non-cacheable status (a {@code 404}, an error, a redirect) is left to the default {@code no-store}.
 *
 * <p><b>The validator outranks the path, and that is what makes the policy safe.</b> The path predicate below is a
 * name test, and a name test cannot tell a frozen artifact from a generated index that happens to carry an extension:
 * every format's enumeration surface but Maven's and npm's does ({@code repodata.json}, {@code repomd.xml},
 * {@code Packages.gz}, {@code packages.json}, {@code index.json}, {@code specs.4.8.gz}). Handed a year of
 * {@code immutable}, a client stops asking, and a version published after it first read the index stays invisible to
 * it - which is not a caching inefficiency but a registry that silently serves a stale catalogue. So the deciding
 * input is not the name: it is whether the response carries an {@code ETag}. The generated indexes go out through the
 * buffered path that sets one; streamed artifact bytes do not. A response that offered a validator is a response the
 * format expects to be revalidated, and it gets {@code no-cache} whatever its name looks like. An artifact that
 * happens to carry an ETag merely loses a year of shared caching, which is the harmless direction to be wrong in.
 *
 * <p><b>No ETag on a streamed body.</b> An ETag derived from the blob key of a streamed body is deliberately not built
 * here: for immutable artifacts {@code Cache-Control: immutable} means clients never revalidate, so a streamed ETag is
 * moot; mutable indexes/metadata already get buffered ETags free-side (untouched here). Doing it properly would be a
 * change to the free core's {@code ServletFormatExchange}, not to this filter.
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
            // Not an artifact serve read (a non-GET/HEAD, or a non-repository route): leave the chain
            // and its default no-store entirely alone.
            chain.doFilter(request, response);
            return;
        }
        CachePolicyResponse wrapped = new CachePolicyResponse(response, policy);
        try {
            chain.doFilter(request, wrapped);
        } finally {
            // Buffered replies (a metadata 200, a 304) never call getOutputStream/getWriter, so apply here too before
            // the container commits - idempotent with the commit-point application a streamed body already made.
            wrapped.applyCachePolicy();
        }
    }

    /** The {@code Cache-Control} value this request's response should carry on a cacheable success, or {@code null}
     *  when the filter must not touch the response at all (leaving the default {@code no-store}). */
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
     * mutable document. Reuses the shared drift predicate {@link HardenedScreen#immutableCoordinate(String)} for
     * the SNAPSHOT test - never a parallel heuristic - then excludes the mutable document families that stay
     * {@code no-cache}: release-level {@code maven-metadata.*}, directory indexes, npm dist-tags, and any
     * extensionless root (an npm packument, a listing). Errs to mutable when unsure.
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

    /** Whether a committed status is a cacheable read success - the only responses that receive the relaxed header;
     *  a {@code 404}/error/redirect keeps the default {@code no-store}. */
    private static boolean cacheable(int status) {
        return status == 200 || status == 206 || status == 304;
    }

    /**
     * Wraps the response the format writes to and stamps {@code Cache-Control} at the format's commit point
     * ({@code getOutputStream}/{@code getWriter}), by which time the status is set (Spring's {@code respond} sets the
     * status before opening the body). Set-if-once and only on a cacheable success, so a {@code 404} that reused this
     * wrapper is never given the relaxed header. Because this runs before the default writer at commit, and that writer
     * is set-if-absent, this value wins - without disabling the default writer for any other route.
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
                // An ETag on this very response is the format SAYING the document is one it expects clients to
                // revalidate - it is set by the buffered path the generated indexes go out through, and never by the
                // streamed artifact path. So it outranks anything guessed from the filename: whatever the path
                // predicate concluded, a document that offered a validator gets no-cache. See immutableArtifact for
                // what this catches that a name test cannot.
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
