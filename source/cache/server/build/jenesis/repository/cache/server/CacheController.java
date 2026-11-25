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
    /** The most bytes a manifest, or a list of digests a build asks for, may hold: text of a line per file. */
    private static final long MANIFEST_LIMIT = 64L << 20;

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
    @RequestMapping(value = "/build/{tenant}/**", method = {RequestMethod.GET, RequestMethod.HEAD, RequestMethod.PUT, RequestMethod.POST})
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
        boolean put = "PUT".equalsIgnoreCase(request.getMethod()), post = "POST".equalsIgnoreCase(request.getMethod());
        switch (protocol.shape(path)) {
            case ENTRY -> {
                if (post) {
                    response.setStatus(405);
                } else if (put) {
                    store(tenant, protocol, address.get(), request, response);
                } else {
                    read(tenant, protocol, address.get(), request, response);
                }
            }
            case MANIFEST -> {
                if (post) {
                    response.setStatus(405);
                } else if (put) {
                    manifest(tenant, protocol, address.get(), request, response);
                } else {
                    read(tenant, protocol, address.get(), request, response);
                }
            }
            case CONTENT -> {
                if (post) {
                    download(tenant, protocol, address.get(), request, response);
                } else if (put) {
                    upload(tenant, protocol, address.get(), request, response);
                } else {
                    response.setStatus(405);
                }
            }
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
    /**
     * Store a step's manifest, or name the files it lacks. The manifest is stored only while the project holds every
     * file it names, so a build that restores it finds them: a build answers {@code 409} by uploading the listed files
     * and sending the manifest again. The check runs for a manifest the project already holds too, as its files may
     * have been evicted since.
     */
    private void manifest(String tenant, CacheProtocol protocol, CacheProtocol.Address address,
                          HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        Cache.Resolution resolution = cache.resolve(tenant, protocol.name(), address.project(), address.key(),
                address.step(), address.inputs(), true);
        if (resolution instanceof Cache.Rejected rejected) {
            challenge(rejected, response);
            return;
        }
        Cache.Allowed allowed = (Cache.Allowed) resolution;
        String algorithm = algorithm(request);
        long contentLength = request.getContentLengthLong();
        if (contentLength < 0) {
            cache.count(allowed.metric(), Cache.Outcome.REJECTED);
            response.setStatus(411);
            return;
        }
        if (contentLength > MANIFEST_LIMIT || cache.tooLarge(contentLength)) {
            cache.count(allowed.metric(), Cache.Outcome.REJECTED);
            response.setStatus(413);
            return;
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes((int) contentLength);
        }
        List<String> lacking;
        try {
            if (!Cache.isDigest(algorithm)) {
                throw new IllegalArgumentException("not a digest this cache names content by: " + algorithm);
            }
            lacking = cache.lacking(allowed, algorithm, body);
        } catch (IllegalArgumentException _) {
            cache.count(allowed.metric(), Cache.Outcome.INVALID);
            response.setStatus(400);
            return;
        }
        if (!lacking.isEmpty()) {
            cache.count(allowed.metric(), Cache.Outcome.MISS);
            response.setStatus(409);
            response.setContentType("text/plain;charset=UTF-8");
            response.getOutputStream().write(String.join("\n", lacking).getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (cache.exists(allowed)) {
            cache.touch(allowed);
            cache.count(allowed.metric(), Cache.Outcome.PRESENT);
            response.setStatus(204);
            return;
        }
        if (cache.cannotFit()) {
            cache.count(allowed.metric(), Cache.Outcome.FULL);
            response.setStatus(507);
            return;
        }
        cache.store(allowed, new ByteArrayInputStream(body));
        cache.count(allowed.metric(), Cache.Outcome.STORED);
        response.setStatus(201);
    }

    /** Answer the files a build asks for by their digests as one zip, an entry per file the project holds, named by
     *  its digest; a file it does not hold is left out, which the build reads as a miss. The files are streamed from
     *  the store into the answer one after another. */
    private void download(String tenant, CacheProtocol protocol, CacheProtocol.Address address,
                          HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        Cache.Resolution resolution = cache.resolve(tenant, protocol.name(), address.project(), address.key(),
                address.step(), address.inputs(), false);
        if (resolution instanceof Cache.Rejected rejected) {
            challenge(rejected, response);
            return;
        }
        Cache.Allowed allowed = (Cache.Allowed) resolution;
        String algorithm = algorithm(request);
        long contentLength = request.getContentLengthLong();
        if (contentLength < 0) {
            cache.count(allowed.metric(), Cache.Outcome.REJECTED);
            response.setStatus(411);
            return;
        }
        if (contentLength > MANIFEST_LIMIT || !Cache.isDigest(algorithm)) {
            cache.count(allowed.metric(), Cache.Outcome.REJECTED);
            response.setStatus(contentLength > MANIFEST_LIMIT ? 413 : 400);
            return;
        }
        Set<String> wanted = new LinkedHashSet<>();
        try (InputStream in = request.getInputStream()) {
            for (String line : new String(in.readNBytes((int) contentLength), StandardCharsets.UTF_8).split("\n")) {
                if (Cache.isContent(algorithm, line.strip())) {
                    wanted.add(line.strip());
                }
            }
        }
        response.setStatus(200);
        response.setContentType("application/zip");
        try (ZipOutputStream zip = new ZipOutputStream(response.getOutputStream())) {
            zip.setLevel(Deflater.BEST_SPEED);
            for (String digest : wanted) {
                Cache.Allowed content = cache.content(allowed, algorithm, digest);
                if (!cache.exists(content)) {
                    cache.count(allowed.metric(), Cache.Outcome.MISS);
                    continue;
                }
                zip.putNextEntry(new ZipEntry(digest));
                cache.read(content, zip);
                zip.closeEntry();
                cache.touch(content);
                cache.count(allowed.metric(), Cache.Outcome.HIT);
            }
        }
    }

    /** Take the files a build uploads as one zip, an entry per file named by its digest, and store each that is what
     *  its name says. One that is not ends the upload with {@code 400}: a build that names a file wrongly is broken or
     *  hostile, and neither should fill the project. */
    private void upload(String tenant, CacheProtocol protocol, CacheProtocol.Address address,
                        HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        Cache.Resolution resolution = cache.resolve(tenant, protocol.name(), address.project(), address.key(),
                address.step(), address.inputs(), true);
        if (resolution instanceof Cache.Rejected rejected) {
            challenge(rejected, response);
            return;
        }
        Cache.Allowed allowed = (Cache.Allowed) resolution;
        String algorithm = algorithm(request);
        if (request.getContentLengthLong() < 0) {
            cache.count(allowed.metric(), Cache.Outcome.REJECTED);
            response.setStatus(411);
            return;
        }
        if (!Cache.isDigest(algorithm)) {
            cache.count(allowed.metric(), Cache.Outcome.INVALID);
            response.setStatus(400);
            return;
        }
        if (cache.cannotFit()) {
            cache.count(allowed.metric(), Cache.Outcome.FULL);
            response.setStatus(507);
            return;
        }
        try (ZipInputStream zip = new ZipInputStream(request.getInputStream())) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!Cache.isContent(algorithm, entry.getName())) {
                    cache.count(allowed.metric(), Cache.Outcome.INVALID);
                    response.setStatus(400);
                    return;
                }
                switch (cache.admit(allowed, algorithm, entry.getName(), zip)) {
                    case STORED -> cache.count(allowed.metric(), Cache.Outcome.STORED);
                    case PRESENT -> cache.count(allowed.metric(), Cache.Outcome.PRESENT);
                    case MISMATCHED -> {
                        cache.count(allowed.metric(), Cache.Outcome.INVALID);
                        response.setStatus(400);
                        return;
                    }
                    case TOO_LARGE -> {
                        cache.count(allowed.metric(), Cache.Outcome.REJECTED);
                        response.setStatus(413);
                        return;
                    }
                }
            }
        }
        response.setStatus(204);
    }

    /** The digest algorithm a request names its files by, SHA-256 where it names none. */
    private static String algorithm(HttpServletRequest request) {
        String named = request.getHeader(CacheProtocol.DIGEST_HEADER);
        return named == null || named.isBlank() ? "SHA-256" : named.strip();
    }

    private static void challenge(Cache.Rejected rejected, HttpServletResponse response) {
        response.setStatus(rejected.status());
        if (rejected.status() == 401) {
            response.setHeader("WWW-Authenticate", "Basic realm=\"Jenesis Cache\"");
        }
    }
}
