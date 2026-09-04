package build.jenesis.repository.format;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;

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
 *     the answer for an unproxyable path, an upstream miss and a refused body (clause 5); the adapter never invents a
 *     {@code 200}, and never leaves a partially written cache entry behind when it declines.
 *     <p><b>A transport failure is not among them on an enumeration document, and this clause used to say it was.</b>
 *     Reading the sentinel as "one answer for every way a fetch can end" is how the adapter's caller comes to render
 *     an unreachable upstream as a plain {@code 404}, and whether that is harmless depends on <em>what the request
 *     addressed</em>:
 *     <ul>
 *     <li>A <b>version-pinned or content-addressed</b> document - an artifact, a per-version manifest or metadata file
 *         the client already resolved to a fixed identity. Here the {@code 404} says "not cached here", the client
 *         re-pulls or falls through to its next configured source, and nothing about a build's resolution is decided
 *         by the absence. {@code false} for every failure is right, and stays right.</li>
 *     <li>An <b>enumeration</b> - a version list, a packument, a PEP 503 simple index, a {@code repodata} or
 *         {@code Packages} index, a {@code maven-metadata.xml}. Here the {@code 404} <em>is</em> an answer: an empty
 *         enumeration a build resolves against, which the client records as a fact about the world ("this package has
 *         no versions") rather than as a reason to retry. Rendering a fetch that never landed as that answer hands the
 *         client a wrong answer it cannot tell from the truth. So on such a document an adapter splits the sentinel by
 *         <em>who said what</em>: an upstream that answered {@code 404}/{@code 410} is a genuine miss and keeps
 *         {@code false}, because an origin said so; a transport failure (clause 6's empty {@link Optional}) or any
 *         other non-{@code 200} - a {@code 429} under a shared egress IP, a {@code 5xx}, an auth challenge - is a
 *         question this repository could not put to its upstream, and it answers {@code 502} and is logged rather
 *         than being answered "none".</li>
 *     </ul>
 *     The classification is the adapter's, because which of a format's paths is a version list is protocol knowledge
 *     only that format has; the rule above is not. It is what {@code versions()}-style local enumerations already do
 *     one layer down, where a scan that stopped at its bound refuses rather than serving a prefix of the versions
 *     (&sect;5, &sect;9) - a list incomplete because the store could not be walked and one empty because the upstream
 *     could not be reached are the same failure wearing different clothes. an earlier change is what it costs when they are not:
 *     a five-second connect timeout served {@code github.com/pkg/errors} as a module with no versions, and the
 *     {@code 404} was investigated for a day as an enumeration regression in this core's paged asset walk.</li>
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
 *     is <em>refused</em>: nothing is linked, nothing is served, and the caller lets the local {@code 404} stand so a
 *     later pull re-hits the upstream. A body is never cached under a digest it does not hash to. An ecosystem that
 *     advertises no digest (a plain file mirror) proxies unverified rather than fabricating a check, and says so.
 *     <p><b>The check runs before anything is linked, never as a retraction afterwards</b>. An adapter may
 *     have to <em>store</em> the body first - that is how a digest is computed while the bytes stream instead of
 *     buffering them - but a stored blob is inert until a pointer references it, so every adapter can verify before
 *     the layout and none has to undo one. The difference is not stylistic: a fill that links first is briefly
 *     serving bytes it has not verified, a failure part-way through the undoing leaves them served for good, and
 *     there is no repair route on this leg at all, because the pointer it failed to remove is exactly what stops the
 *     next pull from being a miss (clause "Read purity": a local hit never touches the upstream). Every leg in this
 *     repository is in that order - OCI always was, Maven since the earlier work - and an adapter in another edition that still
 *     links first and undoes it is a defect against this clause rather than a variation of it.
 *     <p><b>"We could not read the digest" is not "the upstream publishes none", and this clause used to leave that
 *     open.</b> The unverified fall-back above is written for the upstream having <em>published nothing</em> - Maven
 *     serves jars whose {@code .sha1} sibling is missing, Packagist leaves {@code shasum} blank for a VCS-sourced dist
 *     - and every adapter whose digest comes out of a <em>second</em> document was applying it to a case this clause
 *     never covered. A packument fetch that timed out, a compact index behind a shared-egress {@code 429}, a
 *     registration leaf whose advertised URL an outbound screen refuses, a checksum sibling answered by a captive
 *     portal: each of those returned "this ecosystem declares no digest for this artifact", and the artifact was then
 *     cached with no point check at all. That is a silent fail-open, not a wrong answer - anyone able to drop one
 *     sidecar fetch turns this clause's "held to it and a mismatch is refused" off for that pull - and it is the
 *     integrity-surface twin of the split clause 2 makes on the discovery surface. So an adapter splits the same way,
 *     by <em>who said what</em>:
 *     <ul>
 *     <li>the declaring document <b>answered</b> - a {@code 404}/{@code 410}, or a {@code 200} carrying no entry, no
 *         digest field or an unparseable one - and the fall-back above applies unchanged;</li>
 *     <li>the declaring document <b>could not be read</b> - a transport failure (clause 6's empty {@link Optional}),
 *         any other non-{@code 200}, a body that is not the document, a bound the read ran past, or a target an
 *         outbound screen refuses - and the fill is <b>declined</b> exactly as a mismatch is: nothing linked, nothing
 *         served, the local {@code 404} left standing so a later pull re-hits the upstream, and the refusal logged.
 *         An unverifiable artifact is not served on the strength of not having been checked (&sect;5, &sect;9).</li>
 *     </ul>
 *     As with clause 2, the rule is general and the classification is the adapter's, because which fetch declares a
 *     digest is protocol knowledge only that adapter has. An adapter whose digest rides on the artifact's own response,
 *     or whose declaring document is the same one that resolves the download URL, has nothing to split and says
 *     so.</li>
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
     * migration walks any repository that speaks the format's own protocol, including another jenreg. The
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
    interface Fetcher {

        /** The shared fetcher standing in when no upstream-fetcher module is installed: every leg reports a
         *  transport failure. It is a singleton, so a dispatcher can tell "no upstream connectivity" by identity
         *  ({@code fetcher == Fetcher.NONE}) and skip proxying or refuse an import outright rather than failing
         *  request by request. It answers each of the three legs itself rather than deriving one from another: a
         *  fetcher that has no upstream has nothing to derive from, and a {@code HEAD} against it must not have to
         *  open a download to learn that. */
        Fetcher NONE = new Fetcher() {

            @Override
            public Optional<Fetched> fetch(URI url, Map<String, String> requestHeaders) {
                return Optional.empty();
            }

            @Override
            public Optional<Download> download(URI url, Map<String, String> requestHeaders) {
                return Optional.empty();
            }

            @Override
            public Optional<Head> head(URI url, Map<String, String> requestHeaders) {
                return Optional.empty();
            }

            @Override
            public String toString() {
                return "Fetcher.NONE";
            }
        };

        /**
         * Fetch a small upstream document <em>whole</em> - an index, a metadata document, a manifest - so a proxy can
         * inspect or rewrite it. This is the buffered leg, and it is the only one: an artifact body never travels
         * through it (&sect;1), and a transport caps what it will buffer here.
         */
        Optional<Fetched> fetch(URI url, Map<String, String> requestHeaders) throws IOException;

        /**
         * Open a streaming download of an upstream {@code GET}, so a large artifact copies straight from the network
         * to storage rather than being buffered whole (as {@link #fetch} does for the small bodies a proxy must
         * inspect or rewrite). An empty result is a transport failure; otherwise the {@link Download} carries the
         * status and the body stream, so the caller acts on a non-{@code 200} itself - an import fails, a proxy lets
         * the local {@code 404} stand - rather than the fetcher throwing. The caller owns and closes the
         * {@link Download}.
         *
         * <p>This is <em>not</em> a convenience over {@link #fetch}: the difference between the two is the whole
         * streaming principle, so it is declared, never inherited. A fetcher that has no streaming transport says so
         * by implementing {@link Buffered} instead.
         */
        Optional<Download> download(URI url, Map<String, String> requestHeaders) throws IOException;

        /**
         * Ask the upstream for a {@code GET}'s status and response headers <em>without</em> its body - the size, content
         * type, {@code ETag} / {@code Last-Modified} and auth challenge a {@code HEAD} is served from, so a repository
         * can answer a client {@code HEAD} (or size-probe a candidate) without pulling the artifact. An empty result is
         * a transport failure and a non-{@code 200} rides in the {@link Head}'s status, mirroring {@link #fetch} /
         * {@link #download} - the caller acts on the status rather than the fetcher throwing. {@link #NONE} answers
         * empty here as it does for every capability.
         *
         * <p>A real transport issues an actual HTTP {@code HEAD}, so the body is never opened at all and a huge
         * uncached artifact's {@code HEAD} costs a header exchange rather than a body transfer - the
         * metadata-answered {@code HEAD} the streaming principle calls for. Like {@link #download} it is declared
         * rather than inherited, because the only way to derive it is to open a body to answer a question about
         * metadata, and a decorator that inherited such a derivation would silently discard the real {@code HEAD} of
         * the transport it wraps.
         */
        Optional<Head> head(URI url, Map<String, String> requestHeaders) throws IOException;

        /**
         * Fetch a document the walk cannot proceed without, raising {@link Unavailable} where the upstream did not
         * deliver one. It is the preamble every enumeration wrote out for itself - an empty answer, then a
         * non-{@code 200} - written once, so the classification clause 2 states cannot drift from one format to the
         * next. A leg that reads some status as an answer rather than a failure (a {@code 404} meaning "this package
         * contributes nothing") keeps its own branch and calls the {@link Unavailable} factories directly.
         *
         * <p>It relies on clause 6 being kept: a transport that hands out an exception instead of the empty answer
         * for a refused connection would arrive here as neither branch. The shipped {@code HttpFetcher} folds all of
         * them, and an implementation that does not is in breach of the clause rather than of this method.
         */
        static Fetched required(Fetcher fetcher, URI url) throws IOException {
            Optional<Fetched> fetched = fetcher.fetch(url, Map.of());
            if (fetched.isEmpty()) {
                throw Unavailable.noResponse(url);
            }
            if (fetched.get().status() != 200) {
                throw Unavailable.status(url, fetched.get().status());
            }
            return fetched.get();
        }


        /**
         * A fetcher that answers only the buffered {@link #fetch} and lets the other two legs be <em>derived</em> from
         * it: {@link #download} is materialised out of the buffered body, and {@link #head} opens that download for
         * its status and headers. Both derivations violate the streaming clause of the interface they implement,
         * which is exactly why they live behind a name a class has to write down rather than behind a {@code default}
         * a class receives for saying nothing.
         *
         * <p><strong>What this type is for.</strong> A degenerate or scripted upstream - a test double answering from
         * a map, a canned-index fetcher, a fixture - whose whole answer is a small in-memory document. There the
         * derivation costs nothing, because there is no artifact and no network.
         *
         * <p><strong>What it is not for.</strong> A transport, and a <em>decorator</em> over one. A transport that
         * implements this buffers every proxied artifact in heap; a decorator that implements it is worse, because it
         * throws away the real {@link #download} and {@link #head} of the fetcher it wraps and replaces them with
         * derivations - a credential wrapper, a screen or a probe that did this would collapse a deployment's
         * streaming path back onto the buffered one without any of its own code saying so. A decorator delegates all
         * three legs; that is not boilerplate, it is the declaration that it kept them.
         */
        @FunctionalInterface
        interface Buffered extends Fetcher {

            @Override
            default Optional<Download> download(URI url, Map<String, String> requestHeaders) throws IOException {
                return fetch(url, requestHeaders).map(response ->
                        new Download(response.status(), new ByteArrayInputStream(response.body()),
                                response.headers()));
            }

            @Override
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

    /**
     * The upstream did not deliver the document at all - nothing came back (clause 6's empty {@link Optional}), or
     * what came back was the peer declining rather than a document: a {@code 5xx}, a {@code 429} under a shared
     * egress IP, an auth challenge. It is a fact about the peer's availability, never about the payload's shape.
     *
     * <p><b>Clause 2 already draws this line for the proxy leg; this is the enumeration leg finally honouring it.</b>
     * There, "a question this repository could not put to its upstream" answers {@code 502} rather than being
     * rendered as an empty enumeration, because an empty enumeration is one a build records as a fact about the
     * world. The walks reach the identical fork and used to flatten it: {@code No response from ...} and
     * {@code Index fetch failed (503) ...} were thrown as a bare {@link IOException}, and so were {@code No flat
     * container (PackageBaseAddress) advertised by ...} and {@code Not enumerable: neither a catalog nor a search
     * service advertised by ...}. The first pair means the mirror is down. The second means the mirror is up and
     * our walk is now wrong about its format.
     *
     * <p>They call for opposite handling, which is why one type may not carry both. The first is transient - retry
     * it, back off, stand in for it, skip the cell that needed it. The second is permanent and has to be seen: it is
     * a format change, and swallowing it is how a walk goes on quietly passing over a repository it can no longer
     * enumerate. Separating them by matching on the message string is the shape that goes stale without saying so,
     * so the classification is a type and the message is left free to change.
     *
     * <p>A walk therefore raises this one <em>only</em> where the upstream failed to deliver, and lets a plain
     * {@link IOException} carry every judgement about a document that did arrive. A caller meaning "the mirror is
     * having a bad day" catches this; catching {@link IOException} to get it would take the format change too.
     */
    final class Unavailable extends IOException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final URI url;

        private final int status;

        private Unavailable(String message, URI url, int status) {
            super(message);
            this.url = url;
            this.status = status;
        }

        /** Nothing answered at all - a refused connection, a timeout, a DNS failure: clause 6's empty result. */
        public static Unavailable noResponse(URI url) {
            return new Unavailable("No response from " + url, url, 0);
        }

        /** The peer answered, but with a status that is not a document, so the question went unanswered. */
        public static Unavailable status(URI url, int status) {
            return new Unavailable("Index fetch failed (" + status + ") for " + url, url, status);
        }

        /** The document that could not be obtained, so a caller names the mirror rather than re-parsing a message. */
        public URI url() {
            return url;
        }

        /** The status the peer answered with, or empty where nothing answered at all. */
        public OptionalInt status() {
            return status == 0 ? OptionalInt.empty() : OptionalInt.of(status);
        }
    }

}
