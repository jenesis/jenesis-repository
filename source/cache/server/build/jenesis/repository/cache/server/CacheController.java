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
 * The HTTP surface of the cache: it finds the protocol that owns a request path, and serves the address that
 * protocol reads off it onto {@link Cache}.
 *
 * <p>It speaks no wire format itself. Each one - the product's own, Gradle's, Bazel's, the Maven build-cache
 * extension's - is a discovered {@code CacheProtocol} in a module of its own, so which formats a node serves is
 * which modules it carries and an edition ships a subset by shipping fewer. What stays here is everything they
 * share and would otherwise each reimplement: the existence probe a HEAD pays, the read that streams a hit and
 * turns a concurrently reaped entry into the miss it really is, the length and capacity refusals a PUT makes
 * before reading a body, the outcome counters and the challenge.
 *
 * <p>The body is an opaque blob streamed straight to and from storage, and a PUT answers 204/403 before reading
 * it, so an {@code Expect: 100-continue} client skips the upload.
 */
@RestController
public class CacheController {

    /** The node's own admin surface presents its identity the way the native protocol does; one definition. */
    static final String PROJECT = CacheProtocol.PROJECT_HEADER;

    static final String KEY = CacheProtocol.KEY_HEADER;

    /** Where every tenant's cache is addressed: {@code /build/<tenant>/...}. */
    static final String ROOT = "/build/";

    private final Cache cache;

    public CacheController(Cache cache) {
        this.cache = cache;
    }

    private void read(String tenant, CacheProtocol.Address address, HttpServletRequest request,
                      HttpServletResponse response)
            throws IOException {
        Cache.Resolution resolution = cache.resolve(tenant, address.project(), address.key(),
                address.step(), address.inputs(), false);
        if (resolution instanceof Cache.Rejected rejected) {
            challenge(rejected, response);
            return;
        }
        Cache.Allowed allowed = (Cache.Allowed) resolution;
        if ("HEAD".equalsIgnoreCase(request.getMethod())) {
            // A HEAD reports presence without a body, so it still pays the one existence probe a body-less answer needs.
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
        // A GET does not pre-probe existence: the read below already streams the hit, and its recovery block turns a
        // missing (or concurrently reaped) entry into the same 404 MISS an explicit exists() check would have - so the
        // read-mostly hot path pays one object-store round trip on a hit, not a HEAD then a GET. Recency is stamped
        // after the read, which proved the entry exists: stamped ahead of it, a miss - every step of a cold build -
        // asked the store for the stamps of an entry that was not there and then whether it existed.
        response.setStatus(200);
        OutputStream out = response.getOutputStream();
        try {
            cache.read(allowed, out);
        } catch (IOException e) {
            // The entry may be absent (a never-stored key) or deleted by the eviction/reaper thread before or during
            // the read. While nothing has been written the response can still become the miss it really is; a failure
            // mid-body can only abort. The stream is deliberately not closed on this path - closing it would commit
            // the 200 first.
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
     * Find the protocol that owns this path and serve the address it reads.
     *
     * <p>One mapping rather than eight: the protocols are discovered, so which wire formats a node speaks is
     * which modules it carries, and an edition ships a subset by shipping fewer of them. Order is irrelevant
     * because claims are disjoint by contract - {@code CacheProtocol.RESERVED} is what makes that true of a
     * tenant's shared cache, where Gradle's layout is otherwise shape-identical to the native one. The tenant is the
     * URL's, {@code /build/<tenant>/...}, and the key presented decides whether it may be addressed.
     *
     * <p><b>The path is decoded segment by segment</b>, because that is what the {@code @PathVariable} handlers
     * this replaces did and a raw request URI is not. Re-joining the decoded segments differs from per-variable
     * decoding in exactly one case - a segment containing an encoded separator - which cannot arise here: the
     * servlet container refuses an encoded slash in a path by default, and every address these protocols read is
     * hex or an opaque key.
     */
    @RequestMapping(value = "/build/{tenant}/**", method = {RequestMethod.GET, RequestMethod.HEAD, RequestMethod.PUT})
    public void dispatch(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // /build/<tenant>/<path>: the tenant whose cache is addressed, and the path within it the protocols read.
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
            // No protocol claims it: this node does not speak that wire format, which is a 404 rather than a 400
            // - the path is not malformed, it is simply not served here.
            response.setStatus(404);
            return;
        }
        Optional<CacheProtocol.Address> address = protocol.address(new ServletRequest(request, path));
        if (address.isEmpty()) {
            // Claimed but unreadable: the contract's clause 4, and the one case that is the client's mistake.
            response.setStatus(400);
            return;
        }
        if ("PUT".equalsIgnoreCase(request.getMethod())) {
            store(tenant, address.get(), request, response);
        } else {
            read(tenant, address.get(), request, response);
        }
    }

    /** The request path with each segment decoded, which is what the mapped handlers used to be handed. */
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

    private void store(String tenant, CacheProtocol.Address address, HttpServletRequest request,
                       HttpServletResponse response)
            throws IOException {
        Cache.Resolution resolution = cache.resolve(tenant, address.project(), address.key(),
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
            // A chunked (unknown-length) body would bypass the size cap entirely (-1 compares under any max), so
            // one PUT could stream unbounded bytes to the volume. The build-tool clients always send a fixed
            // length, so requiring one refuses nothing legitimate.
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

    /**
     * Refuse, and on a 401 say how to authenticate.
     *
     * <p>A bare 401 is only usable by a client that sends its credential unasked. The native clients do - the key
     * rides a header they always set - so this was invisible until a client arrived that waits to be challenged.
     * Maven Resolver is one: it sends the GET without credentials, and with no {@code WWW-Authenticate} it never
     * retries, so the read half of the Maven cache could not authenticate at all. The write half worked, because
     * Resolver sends credentials unasked on an upload - which is why the symptom was a cache that stored perfectly
     * and never hit, reported by the extension as "Remote cache is incomplete or missing".
     */
    private static void challenge(Cache.Rejected rejected, HttpServletResponse response) {
        response.setStatus(rejected.status());
        if (rejected.status() == 401) {
            response.setHeader("WWW-Authenticate", "Basic realm=\"Jenesis Cache\"");
        }
    }
}
