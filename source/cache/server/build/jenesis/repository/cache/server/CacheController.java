package build.jenesis.repository.cache.server;

import module java.base;

import java.nio.charset.StandardCharsets;
import build.jenesis.repository.cache.protocol.CacheProtocol;
import build.jenesis.repository.server.PresentedKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP surface of the cache: it finds the protocol owning a request path and serves the address it reads onto
 * {@link Cache}. Each wire format is a discovered {@code CacheProtocol} in its own module, so a node serves what it
 * carries. What they share stays here: the existence probe a HEAD pays, the read that streams a hit and turns a reaped
 * entry into a miss, the length and capacity refusals before a PUT's body, the counters and the challenge.
 *
 * <p>The body is an opaque blob streamed to and from storage, and a PUT answers 204/403 before reading it, so an
 * {@code Expect: 100-continue} client skips the upload.
 */
@RestController
public class CacheController {

    /** The node's admin surface presents identity as the native protocol does. */
    static final String PROJECT = CacheProtocol.PROJECT_HEADER;

    static final String KEY = CacheProtocol.KEY_HEADER;

    /** Where every tenant's cache is addressed: {@code /build/<tenant>/...}. */
    static final String ROOT = "/build/";

    private final Cache cache;

    public CacheController(Cache cache) {
        this.cache = cache;
    }

    private void read(String tenant, CacheProtocol protocol, CacheProtocol.Address address,
                      HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        Cache.Resolution resolution = cache.resolve(tenant, protocol.name(), address.project(), address.key(),
                address.step(), address.inputs(), false);
        if (resolution instanceof Cache.Rejected rejected) {
            challenge(rejected, response);
            return;
        }
        Cache.Allowed allowed = (Cache.Allowed) resolution;
        if ("HEAD".equalsIgnoreCase(request.getMethod())) {
            // A HEAD has no body, so it pays the existence probe.
            if (!cache.exists(allowed)) {
                cache.count(allowed.metric(), Cache.Outcome.MISS);
                response.setStatus(404);
                return;
            }
            cache.touch(allowed);
            cache.count(allowed.metric(), Cache.Outcome.TOUCHED);
            response.setStatus(200);
            return;
        }
        // A GET does not probe first: the read streams the hit, and its recovery turns a missing or reaped entry into
        // the 404 MISS, so a hit costs one round trip. Recency is stamped after the read proved the entry exists, so a
        // miss stamps nothing.
        response.setStatus(200);
        OutputStream out = response.getOutputStream();
        try {
            cache.read(allowed, out);
        } catch (IOException e) {
            // The entry may be absent or reaped during the read. Before anything is written the response can still
            // become a miss; mid-body it can only abort. The stream is not closed here, which would commit the 200.
            if (response.isCommitted()) {
                throw e;
            }
            response.reset();
            cache.count(allowed.metric(), Cache.Outcome.MISS);
            response.setStatus(404);
            return;
        }
        cache.touch(allowed);
        cache.count(allowed.metric(), Cache.Outcome.HIT);
        out.close();
    }

    /**
     * Find the protocol that owns this path and serve the address it reads. Claims are disjoint by contract -
     * {@code CacheProtocol.RESERVED} keeps Gradle's layout apart from the native one - so order does not matter. The
     * tenant is the URL's ({@code /build/<tenant>/...}), and the key decides whether it may be addressed.
     *
     * <p>The path is decoded segment by segment. Re-joining decoded segments differs from decoding each only for an
     * encoded separator, which the servlet container refuses by default, and every address the protocols read is hex or
     * an opaque key.
     */
    @RequestMapping(value = "/build/{tenant}/**", method = {RequestMethod.GET, RequestMethod.HEAD, RequestMethod.PUT})
    public void dispatch(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // /build/<tenant>/<path>: the addressed tenant, and the path the protocols read.
        String decoded = decoded(request).substring(ROOT.length());
        int slash = decoded.indexOf('/');
        String tenant = slash < 0 ? decoded : decoded.substring(0, slash);
        String path = slash < 0 ? "/" : decoded.substring(slash);
        CacheProtocol protocol = null;
        for (CacheProtocol candidate : CacheProtocol.installed()) {
            if (candidate.handles(path)) {
                protocol = candidate;
                break;
            }
        }
        if (protocol == null) {
            // No protocol claims it: this node does not speak that format, a 404.
            response.setStatus(404);
            return;
        }
        Optional<CacheProtocol.Address> address = protocol.address(new ServletRequest(request, path));
        if (address.isEmpty()) {
            // Claimed but unreadable: clause 4, the client's mistake.
            response.setStatus(400);
            return;
        }
        if ("PUT".equalsIgnoreCase(request.getMethod())) {
            store(tenant, protocol, address.get(), request, response);
        } else {
            read(tenant, protocol, address.get(), request, response);
        }
    }

    /** The request path with each segment decoded. */
    private static String decoded(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        StringJoiner joined = new StringJoiner("/");
        for (String segment : uri.split("/", -1)) {
            joined.add(URLDecoder.decode(segment, StandardCharsets.UTF_8));
        }
        return joined.toString();
    }

    /** The servlet request as a protocol reads it: its own headers, and the presentation every protocol shares. */
    private record ServletRequest(HttpServletRequest request, String path) implements CacheProtocol.Request {

        @Override
        public String method() {
            return request.getMethod().toUpperCase(Locale.ROOT);
        }

        @Override
        public String header(String name) {
            return request.getHeader(name);
        }

        @Override
        public String project() {
            return PresentedKey.user(request);
        }

        @Override
        public String presentedKey() {
            return PresentedKey.from(request);
        }
    }

    private void store(String tenant, CacheProtocol protocol, CacheProtocol.Address address,
                       HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        Cache.Resolution resolution = cache.resolve(tenant, protocol.name(), address.project(), address.key(),
                address.step(), address.inputs(), true);
        if (resolution instanceof Cache.Rejected rejected) {
            challenge(rejected, response);
            return;
        }
        Cache.Allowed allowed = (Cache.Allowed) resolution;
        if (address.existing() == CacheProtocol.Existing.DEDUPE && cache.exists(allowed)) {
            cache.count(allowed.metric(), Cache.Outcome.PRESENT);
            response.setStatus(204);
            return;
        }
        long contentLength = request.getContentLengthLong();
        if (contentLength < 0) {
            // A chunked body would bypass the size cap, so a length is required; build-tool clients always send one.
            cache.count(allowed.metric(), Cache.Outcome.REJECTED);
            response.setStatus(411);
            return;
        }
        if (cache.tooLarge(contentLength)) {
            cache.count(allowed.metric(), Cache.Outcome.REJECTED);
            response.setStatus(413);
            return;
        }
        if (cache.cannotFit()) {
            cache.count(allowed.metric(), Cache.Outcome.FULL);
            response.setStatus(507);
            return;
        }
        try (InputStream in = request.getInputStream()) {
            cache.store(allowed, in);
        }
        cache.count(allowed.metric(), Cache.Outcome.STORED);
        response.setStatus(201);
    }

    /** Refuse, and on a 401 say how to authenticate: a client that waits to be challenged needs
     *  {@code WWW-Authenticate}. Maven Resolver sends a GET without credentials and never retries unchallenged, so
     *  without it the Maven cache would store but never hit. */
    private static void challenge(Cache.Rejected rejected, HttpServletResponse response) {
        response.setStatus(rejected.status());
        if (rejected.status() == 401) {
            response.setHeader("WWW-Authenticate", "Basic realm=\"Jenesis Cache\"");
        }
    }
}
