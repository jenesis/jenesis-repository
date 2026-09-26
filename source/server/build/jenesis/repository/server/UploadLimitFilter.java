package build.jenesis.repository.server;

import module java.base;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bounds what one request may send, answering {@code 413}: a body declaring a length past {@code upload-max-bytes} is
 * refused before a byte of it is read, and one that streams without declaring a length is refused at the byte that
 * crosses the bound, so nothing of it is published. A publish streams to the store rather than into memory, so what
 * this bounds is the store and the time one request may hold - a credential that may publish may otherwise send
 * whatever it likes, and a proxy in front often sets no limit of its own.
 *
 * <p>The bound is per request, which for a registry that uploads in chunks is per chunk and for every other client is
 * the artifact. It is read live, like the rate limit beside it; {@code 0} lifts it.
 */
public class UploadLimitFilter extends OncePerRequestFilter {

    /** The setting's key. */
    public static final String KEY = "upload-max-bytes";

    /** Ten gibibytes: past the largest container layer a public registry accepts, far past any library. The text is
     *  the default the catalogue declares; the number is the same value for the code that reads it. */
    public static final String DEFAULT_TEXT = "10737418240";

    static final long DEFAULT = Long.parseLong(DEFAULT_TEXT);

    private static final Set<String> BODILESS = Set.of("GET", "HEAD", "OPTIONS", "TRACE", "DELETE");

    private final LongSupplier limit;

    public UploadLimitFilter(LongSupplier limit) {
        this.limit = limit;
    }

    /** The bound as {@code lookup} reads it now: the stored setting, else {@link #DEFAULT}; a value that does not
     *  parse, or is negative, is the default rather than no bound. */
    public static LongSupplier live(UnaryOperator<String> lookup) {
        return () -> {
            String configured = lookup.apply("jenreg." + KEY);
            if (configured == null || configured.isBlank()) {
                return DEFAULT;
            }
            try {
                long bound = Long.parseLong(configured.trim());
                return bound < 0 ? DEFAULT : bound;
            } catch (NumberFormatException notANumber) {
                return DEFAULT;
            }
        };
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long bound = limit.getAsLong();
        if (bound == 0 || BODILESS.contains(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > bound) {
            response.sendError(413, "The request body is past " + KEY + "=" + bound);
            return;
        }
        Bounded bounded = new Bounded(request, bound);
        try {
            chain.doFilter(bounded, response);
        } catch (IOException | ServletException | RuntimeException failed) {
            if (!bounded.exceeded) {
                throw failed;
            }
        }
        if (bounded.exceeded && !response.isCommitted()) {
            response.reset();
            response.sendError(413, "The request body is past " + KEY + "=" + bound);
        }
    }

    /** The request whose body stops at the bound: the read that would cross it fails, and the request remembers
     *  that it did, so the answer is the bound's whatever the handler made of the failure. */
    private static final class Bounded extends HttpServletRequestWrapper {

        private final long bound;
        private boolean exceeded;
        private ServletInputStream stream;

        private Bounded(HttpServletRequest request, long bound) {
            super(request);
            this.bound = bound;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                ServletInputStream body = super.getInputStream();
                stream = new ServletInputStream() {

                    private long read;

                    @Override
                    public int read() throws IOException {
                        int one = body.read();
                        if (one >= 0) {
                            count(1);
                        }
                        return one;
                    }

                    @Override
                    public int read(byte[] bytes, int offset, int length) throws IOException {
                        int count = body.read(bytes, offset, length);
                        if (count > 0) {
                            count(count);
                        }
                        return count;
                    }

                    private void count(long bytes) throws IOException {
                        read += bytes;
                        if (read > bound) {
                            exceeded = true;
                            throw new IOException("The request body is past " + KEY + "=" + bound);
                        }
                    }

                    @Override
                    public boolean isFinished() {
                        return body.isFinished();
                    }

                    @Override
                    public boolean isReady() {
                        return body.isReady();
                    }

                    @Override
                    public void setReadListener(ReadListener listener) {
                        body.setReadListener(listener);
                    }
                };
            }
            return stream;
        }
    }
}
