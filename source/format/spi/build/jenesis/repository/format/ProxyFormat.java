package build.jenesis.repository.format;

import build.jenesis.repository.store.ArtifactStore;

import module java.base;

/**
 * The optional pull-through capability of a {@link RepositoryFormat}: a format that also implements this serves a
 * local miss from an upstream repository, so the repository is a build's single front door rather than a
 * publish-only store. The adapter owns the whole upstream interaction through {@link #proxy} - it maps the request
 * to its upstream, fetches (with whatever request headers or auth the protocol needs, including a multi-step token
 * handshake), caches an immutable artifact and serves it, or rewrites and streams a mutable index. The dispatcher
 * only detects the local miss and hands control over; keeping this off the {@link RepositoryFormat} contract means
 * a hosted-only format is unaffected.
 *
 * <h2>Contract</h2>
 * This is a role sub-interface of {@link RepositoryFormat}: every clause of that contract still binds, and the clauses
 * below add what pulling from an upstream changes. They are executable through {@code FormatContract}'s proxy leg in
 * the format testkit, so upstream integrity and streaming are proven per format rather than argued per format.
 * <ol>
 * <li><b>Idempotency / replay.</b> {@link #proxy} is a cache fill, so a repeated pull of the same immutable artifact
 *     converges on the same stored blob and serves identical bytes; a mutable index is never cached, so a repeat
 *     re-reads the upstream rather than serving something a previous request happened to keep.</li>
 * <li><b>Absence sentinel.</b> {@code false} means "this leg served nothing - let the local {@code 404} stand". It is
 *     the answer for an unproxyable path, an upstream miss, a transport failure <em>and</em> a refused body (clause 5);
 *     the adapter never invents a {@code 200}, and never leaves a partially written cache entry behind when it
 *     declines.</li>
 * <li><b>Streaming (&sect;1).</b> An artifact is copied from the network into the content-addressed store through
 *     {@link Fetcher#download} without ever being materialised - the store is handed the still-unread upstream stream,
 *     never a buffer the adapter filled first - so a multi-gigabyte pull stays bounded in heap. Only a small mutable
 *     index or a manifest the adapter must parse or rewrite may use the buffered {@link Fetcher#fetch}, and a
 *     {@code HEAD} uses {@link Fetcher#head}, which opens no body at all.</li>
 * <li><b>Read purity (&sect;10).</b> A proxy fetch is the one sanctioned exception to the read path rendering only
 *     stored state, and it is entered solely on a local miss of a path this format claims. A local hit never touches
 *     the upstream.</li>
 * <li><b>Upstream integrity.</b> Where the ecosystem's own protocol advertises a digest for the bytes - a
 *     content-addressed reference, a checksum sibling, a digest header - the fetched body is held to it and a mismatch
 *     is <em>refused</em>: nothing is linked, nothing is served, every view the fill had already laid out is retracted,
 *     and the caller lets the local {@code 404} stand so a later pull re-hits the upstream. A body is never cached
 *     under a digest it does not hash to. An ecosystem that advertises no digest (a plain file mirror) proxies
 *     unverified rather than fabricating a check, and says so.</li>
 * <li><b>Error visibility (&sect;9).</b> An upstream error status rides in the {@link Fetched} / {@link Download} /
 *     {@link Head} so the adapter acts on it (a {@code 401} challenge, a {@code 404} miss); only a transport failure is
 *     an empty {@link Optional}. A failure while filling the cache may never be swallowed into a served response.</li>
 * <li><b>Bounded work / cancellation.</b> {@link #enumerate} is lazy - an index page is read only as the stream
 *     advances - and every index the adapter reads to serve or enumerate is bounded, so neither a hostile upstream nor
 *     an enormous one can force an unbounded read out of one request. Where an ecosystem's index is <em>compressed</em>
 *     (a gzipped {@code Packages} file, a {@code repodata} document), the bound is on the <b>decompressed</b> size,
 *     for the reason {@link RepositoryFormat}'s inflation clause gives: an upstream chooses the ratio, so a bound on
 *     the transferred bytes is no bound at all. The buffered {@link Fetcher#fetch} carries a response-size ceiling of
 *     its own beneath that ({@link FetcherProvider}'s bounded-work clause), and reaching it is a named failure rather
 *     than a short index parsed as complete.</li>
 * <li><b>Durability / delivery.</b> A cache fill commits pointer-last exactly as a hosted publish does: the blob is
 *     content-addressed first and the serving pointer linked only once the bytes are stored and verified, so a crash
 *     mid-fill leaves an unreferenced blob rather than a pointer to nothing.</li>
 * <li><b>An upstream-supplied name is as untrusted as a client-supplied one.</b> {@link RepositoryFormat}'s traversal
 *     clause binds unchanged on this leg - a traversal-shaped <em>request</em> path is refused before it becomes a
 *     proxy target, so it never reaches the upstream and never lays a fetched body out at a key the store refuses -
 *     and it extends to every name the <em>upstream</em> chooses: a repository name off a catalog, a tag, a filename
 *     an index lists, a path this adapter splices into a layout key. An adapter screens such a name at the point it
 *     composes a key, exactly as it screens a request path. {@link #enumerate} is the one place this obligation is
 *     deliberately <em>not</em> the format's: a {@link Coordinate} carries a path and an absolute URL both derived
 *     from a foreign index, and the SPI promises <b>no</b> safety for either, so its consumer screens them - the free
 *     import walk skips a traversal-laced {@code path} and refuses a cross-origin {@code url} that resolves to a
 *     private, loopback or metadata host. An adapter must therefore not assume its enumerated coordinates were
 *     sanitised on the way out, and a consumer must not assume they were sanitised on the way in.</li>
 * <li><b>A cache fill is not a hosted publish, and is not screened like one.</b> {@link #proxy} writes through the
 *     store directly; it does not run the {@code PublishInterceptor} chain, and it must not - a format runs no screen
 *     of its own ({@link RepositoryFormat}'s store-then-gate clause). What stands between a proxied body and the
 *     serving pointer is therefore clause 5's integrity check and nothing else, so an ecosystem that advertises no
 *     digest caches an unverified body by construction and says so. A deployment that screens proxied bytes installs
 *     that screen at its own proxy edge, not inside a format; the core ships none, so in the free distribution a
 *     proxied artifact is verified and cached but not gated. This asymmetry with the hosted path is deliberate and
 *     stated here because assuming the symmetry is the fail-open mistake.</li>
 * </ol>
 */
public interface ProxyFormat {

    /**
     * Serve a local {@code GET} miss for a path this format handles from the upstream rooted at {@code upstream}.
     * The adapter fetches through {@code fetcher}, caches immutable artifacts (so a later read is a local hit) and
     * streams mutable indexes fresh (rewriting upstream links back to this repository where needed), writing the
     * response through {@code exchange}. Returns {@code true} when it served a response, or {@code false} to let
     * the local {@code 404} stand (an unproxyable path, or an upstream miss).
     */
    boolean proxy(FormatExchange exchange, ArtifactStore store, URI upstream, Fetcher fetcher) throws IOException;

    /**
     * The canonical public upstream this format mirrors when a deployment enables proxying without naming one - the
     * Maven format's Maven Central, an npm format's registry.npmjs.org. A distribution takes its default upstream from
     * the format itself, so it needs no table of format names to know where each format proxies. Empty when the format
     * has no single well-known upstream; a deployment can always set one explicitly per format or per repository.
     */
    default Optional<URI> defaultUpstream() {
        return Optional.empty();
    }

    /**
     * Enumerate every artifact the upstream rooted at {@code upstream} publishes through this format's own
     * mirror-style index - the same PEP 503 project list, V3 catalog, {@code Packages} index or {@code repodata}
     * the format already reads to serve pull-through, pointed at "list everything" instead of "resolve one". The
     * stream is lazy (an index page is only read as the stream advances) and each {@link Coordinate} pairs the
     * layout path the artifact occupies under this format - the path this format's
     * {@link RepositoryImporter} accepts - with the upstream URL its bytes download from, so a vendor-neutral
     * migration walks any repository that speaks the format's own protocol, including another jenesis. The
     * default returns an empty stream: a format whose ecosystem publishes no walkable index (Conan exposes only a
     * search API) simply cannot enumerate, and a caller treats "nothing enumerated" as that format's honest
     * answer. Failures reading the initial index throw; a failure while the stream advances surfaces as an
     * {@link java.io.UncheckedIOException}.
     */
    default Stream<Coordinate> enumerate(Fetcher fetcher, URI upstream) throws IOException {
        return Stream.empty();
    }

    /** One enumerated artifact: the layout {@code path} it occupies under this format (no leading slash, the shape
     *  the format's {@link RepositoryImporter} accepts), the upstream {@code url} its bytes download from, and the
     *  request {@code headers} that download needs (the {@code Accept} an OCI manifest is negotiated with, say) -
     *  empty for a plain download. */
    record Coordinate(String path, URI url, Map<String, String> headers) {

        public Coordinate(String path, URI url) {
            this(path, url, Map.of());
        }
    }

    /**
     * The upstream HTTP fetch, isolated behind an interface so a test answers from a fixed upstream without the
     * network. {@code requestHeaders} are sent upstream (e.g. {@code Accept} for OCI manifest negotiation, an
     * {@code Authorization} bearer token). An empty result is a transport failure; an HTTP error is a
     * {@link Fetched} carrying its status, so the adapter can act on a {@code 401} challenge or a {@code 404}.
     */
    @FunctionalInterface
    interface Fetcher {

        /** The shared fetcher standing in when no upstream-fetcher module is installed: every fetch reports a
         *  transport failure. It is a singleton, so a dispatcher can tell "no upstream connectivity" by identity
         *  ({@code fetcher == Fetcher.NONE}) and skip proxying or refuse an import outright rather than failing
         *  request by request. */
        Fetcher NONE = (url, requestHeaders) -> Optional.empty();

        Optional<Fetched> fetch(URI url, Map<String, String> requestHeaders) throws IOException;

        /**
         * Open a streaming download of an upstream {@code GET}, so a large artifact copies straight from the network
         * to storage rather than being buffered whole (as {@link #fetch} does for the small bodies a proxy must
         * inspect or rewrite). An empty result is a transport failure; otherwise the {@link Download} carries the
         * status and the body stream, so the caller acts on a non-{@code 200} itself - an import fails, a proxy lets
         * the local {@code 404} stand - rather than the fetcher throwing. The caller owns and closes the
         * {@link Download}. The default materializes from {@link #fetch}; a real HTTP fetcher overrides it to stream
         * the response body.
         */
        default Optional<Download> download(URI url, Map<String, String> requestHeaders) throws IOException {
            return fetch(url, requestHeaders).map(response ->
                    new Download(response.status(), new ByteArrayInputStream(response.body()), response.headers()));
        }

        /**
         * Ask the upstream for a {@code GET}'s status and response headers <em>without</em> its body - the size, content
         * type, {@code ETag} / {@code Last-Modified} and auth challenge a {@code HEAD} is served from, so a repository
         * can answer a client {@code HEAD} (or size-probe a candidate) without pulling the artifact. An empty result is
         * a transport failure and a non-{@code 200} rides in the {@link Head}'s status, mirroring {@link #fetch} /
         * {@link #download} - the caller acts on the status rather than the fetcher throwing. {@code Fetcher.NONE}
         * answers empty here as it does for every capability.
         *
         * <p>The default falls back to {@link #download}, reading only the status and headers and then closing the body
         * stream without draining it - correct, but it still opens the upstream body, so an uncached large artifact's
         * {@code HEAD} would open (though never read) its download. A real HTTP fetcher <strong>overrides this with an
         * actual HTTP {@code HEAD} request</strong>, so the body is never opened at all and a huge uncached artifact's
         * {@code HEAD} costs a header exchange, not a body transfer - the metadata-answered {@code HEAD} the streaming
         * principle calls for.
         */
        default Optional<Head> head(URI url, Map<String, String> requestHeaders) throws IOException {
            Optional<Download> download = download(url, requestHeaders);
            if (download.isEmpty()) {
                return Optional.empty();
            }
            try (Download response = download.get()) {
                return Optional.of(new Head(response.status(), response.headers()));
            }
        }
    }

    /** A bodiless upstream response: the HTTP status and the response headers a {@code HEAD} carries (content type,
     *  size via {@code Content-Length}, {@code ETag} / {@code Last-Modified} validators, an auth challenge) with no
     *  body - the {@link ProxyFormat.Fetcher#head} counterpart of {@link Fetched} / {@link Download}. */
    record Head(int status, Map<String, String> headers) {

        /** The first value of a response header, case-insensitively, or {@code null}. */
        public String header(String name) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return null;
        }
    }

    /** A streaming upstream response: the HTTP status, the body stream (which the caller owns and closes, so a
     *  non-{@code 200} is closed without draining it), and the response headers - the latter for the content type and
     *  the auth challenge a streamed proxy fetch still has to read before it decides what to do with the body. */
    record Download(int status, InputStream body, Map<String, String> headers) implements Closeable {

        /** The first value of a response header, case-insensitively, or {@code null}. */
        public String header(String name) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return null;
        }

        @Override
        public void close() throws IOException {
            body.close();
        }
    }

    /** An upstream response: the HTTP status, the body, and the response headers (for content type and auth challenges). */
    record Fetched(int status, byte[] body, Map<String, String> headers) {

        /** The first value of a response header, case-insensitively, or {@code null}. */
        public String header(String name) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return null;
        }
    }
}
