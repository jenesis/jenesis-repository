package build.jenesis.repository.server.kernel;

import module java.base;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

/**
 * Caps the request body on the two unauthenticated write endpoints ({@code POST /api/leaked} and {@code POST
 * /api/token}) so an anonymous caller cannot exhaust memory with a huge POST - their bodies are a small revocation
 * report and an id-token. A declared {@code Content-Length} over the cap is rejected with {@code 413} before the body
 * is read; a body that runs over the cap while streaming (a chunked request, or a lying Content-Length) is cut off by
 * the wrapped input stream, so nothing beyond the cap is ever buffered. The path is decoded first so an encoded route
 * ({@code /api/%6ceaked}) cannot slip past the match. Artifact upload paths are untouched: this guards only the two
 * small routes, matched exactly.
 *
 * <p>The rejection short-circuits with {@code setStatus} rather than {@code sendError} (the same shape the free
 * {@code RateLimitFilter} uses to shed load): running before the authorization filter, a {@code sendError} would
 * trigger the servlet {@code ERROR} dispatch, which the deny-by-default security chain re-authorizes on the forwarded
 * {@code /error} path and turns into a {@code 401}/{@code 403} - masking the {@code 413}. Setting the status and
 * returning writes the {@code 413} straight to the wire without a re-entered dispatch.
 */
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;

    public RequestBodyLimitFilter(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!request.getMethod().equals("POST")) {
            return true;
        }
        String path = UriUtils.decode(request.getRequestURI(), StandardCharsets.UTF_8);
        return !path.equals("/api/leaked") && !path.equals("/api/token");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        chain.doFilter(new LimitedRequest(request, maxBytes), response);
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long limit;

        private LimitedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream delegate = super.getInputStream();
            return new ServletInputStream() {

                private long count;

                @Override
                public int read() throws IOException {
                    int value = delegate.read();
                    if (value != -1 && ++count > limit) {
                        throw new IOException("Request body exceeds " + limit + " bytes");
                    }
                    return value;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int read = delegate.read(buffer, offset, length);
                    if (read > 0 && (count += read) > limit) {
                        throw new IOException("Request body exceeds " + limit + " bytes");
                    }
                    return read;
                }

                @Override
                public boolean isFinished() {
                    return delegate.isFinished();
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    delegate.setReadListener(listener);
                }
            };
        }
    }
}
