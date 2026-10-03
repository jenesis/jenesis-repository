package build.jenesis.repository.blobs;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.OwnerOnly;
import build.jenesis.repository.store.UpstreamMemory;


/**
 * The proxy-relay mechanics every pull-through format shares, held once so a format module carries only its
 * protocol logic: forward the client's conditional-request validators upstream, relay the upstream's cache
 * validators back to the client, parse an upstream {@code Content-Length} - and decide what it means when the
 * upstream does not answer at all. The semantics are fixed here - which headers ride in each direction, that an
 * absent or unparseable length streams the body rather than failing the serve, and which upstream outcomes a client
 * may be told are the upstream's own answer - so a new format inherits them instead of re-deriving them subtly
 * differently.
 */
public final class ProxyRelay {

    private ProxyRelay() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ProxyRelay.class);

    /** The client's conditional-request validators ({@code If-None-Match} / {@code If-Modified-Since}), forwarded
     *  upstream so a 304-capable client's revalidation reaches the origin rather than being dropped. Empty when the
     *  client sent none; the returned map is mutable, so a caller adds the protocol headers its fetch needs (an
     *  {@code Accept}, say). */
    public static Map<String, String> conditionalHeaders(FormatExchange exchange) {
        Map<String, String> headers = new LinkedHashMap<>();
        String ifNoneMatch = exchange.requestHeader("If-None-Match");
        if (ifNoneMatch != null) {
            headers.put("If-None-Match", ifNoneMatch);
        }
        String ifModifiedSince = exchange.requestHeader("If-Modified-Since");
        if (ifModifiedSince != null) {
            headers.put("If-Modified-Since", ifModifiedSince);
        }
        return headers;
    }

    /** Relay a streamed upstream response's cache validators ({@code ETag} / {@code Last-Modified}) to the client,
     *  so its next read can revalidate against them. */
    public static void relayValidators(ProxyFormat.Download download, FormatExchange exchange) {
        relay(download.header("ETag"), download.header("Last-Modified"), exchange);
    }

    /** Relay a buffered upstream response's cache validators ({@code ETag} / {@code Last-Modified}) to the client,
     *  so its next read can revalidate against them. */
    public static void relayValidators(ProxyFormat.Fetched response, FormatExchange exchange) {
        relay(response.header("ETag"), response.header("Last-Modified"), exchange);
    }

    private static void relay(String etag, String lastModified, FormatExchange exchange) {
        if (etag != null) {
            exchange.setResponseHeader("ETag", etag);
        }
        if (lastModified != null) {
            exchange.setResponseHeader("Last-Modified", lastModified);
        }
    }

    /** Parse a {@code Content-Length} header to a length, or {@code -1} (an unknown length streams the body). */
    public static long length(String header) {
        if (header == null) {
            return -1L;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /**
     * What a leg is relaying, which is the whole of what "the upstream did not answer" is allowed to become. Every
     * relay through this class names one, because the choice cannot be derived here: which of a format's paths is a
     * version list and which is a version-pinned file is the protocol knowledge only the format has, and it is the one
     * input the shared decision below needs.
     *
     * <p>The distinction is needed because {@link ProxyFormat} clause 2 makes a single {@code false} - "let the local
     * {@code 404} stand" - the answer for an unproxyable path, an upstream miss, a transport failure and a refused body
     * alike, so a leg that could not reach its upstream answers exactly as a leg whose upstream said "no such thing".
     * On a {@link #PINNED} document that is right; on an {@link #ENUMERATION} it hands the client a wrong answer it
     * cannot tell from the truth.
     */
    public enum Document {

        /**
         * A document whose <em>content or absence answers a discovery question</em> - which versions exist, which
         * packages, which files: a packument, a PEP 503 simple index, a {@code repodata} / {@code Packages} /
         * {@code repomd} index, a sparse-index crate file, a {@code @v/list}, a compact-index {@code info} line, a
         * flat-container {@code index.json}, a Conan revisions listing, a CocoaPods shard listing.
         *
         * <p>Here a {@code 404} is not "the leg served nothing" - it <b>is</b> an answer, an empty enumeration a build
         * resolves against, and the client records it as a fact about the world ("this package has no versions", "this
         * component is empty") rather than as a reason to re-pull. So the two halves of clause 2's sentinel are split
         * apart: only an upstream that <em>answered</em> {@code 404}/{@code 410} may reach the client as a {@code 404},
         * because an origin said it. A transport failure, or an upstream that answered something other than the
         * document, is a question this repository could not put to its upstream, and it fails visibly with a
         * {@code 502} instead of being answered "none".
         */
        ENUMERATION,

        /**
         * A document the client has <em>already resolved to a fixed identity</em> - a version-pinned or
         * content-addressed artifact or its per-version metadata: a tarball, a {@code .crate}, a {@code .nupkg}, a
         * {@code .gem}, a {@code .deb}, an {@code .rpm}, a Go {@code .info}/{@code .mod}/{@code .zip}, a per-version
         * podspec, a {@code quick} gemspec, an upstream source tarball.
         *
         * <p>Here clause 2's {@code false} stays exactly right and is deliberately kept: the {@code 404} says "not
         * cached here", the client re-pulls (or, for a {@code go} client, falls through to the next {@code GOPROXY}),
         * and nothing about a build's resolution is decided by the absence. Turning these into {@code 502}s would
         * break that fall-through for no gain, so this value is not a lesser form of the one above - it is the
         * classification a leg makes on purpose.
         */
        PINNED
    }

    /**
     * Whether an upstream status is the upstream's <em>own answer</em> that it carries no such thing - the one shape
     * of absence a client may act on, because an origin said it. {@code 410} counts with {@code 404}: a package the
     * upstream has deliberately retired is still an origin answering the question.
     *
     * <p>Everything else is not an answer. A {@code 429} under a shared egress IP, a {@code 5xx}, an auth challenge, a
     * redirect the transport would not follow - each of those is the upstream declining to answer, and rendering a
     * decline as an empty enumeration is the defect this class exists to prevent.
     */
    public static boolean upstreamMiss(int status) {
        return status == 404 || status == 410;
    }

    /**
     * The verdict for an upstream that did not answer the question - a transport failure (the SPI's empty-
     * {@link Optional} sentinel) or a status that is neither the document nor {@link #upstreamMiss a miss}. On a
     * {@link Document#PINNED} relay it is {@code false}, the contract's sentinel, unchanged. On a
     * {@link Document#ENUMERATION} it answers {@code 502}, says in the log which target failed and how, and returns
     * {@code true} - because the leg <em>did</em> serve a response, and the {@code false} that means "let the local
     * {@code 404} stand" would have let a lie stand.
     *
     * <p>The transport reports a timeout as the empty result, so a network blip and a real absence reach a leg in the
     * same shape. The rule lives here rather than once per proxying format so a new format cannot reopen the hole by
     * writing the obvious {@code return false}.
     */
    public static boolean unanswered(URI target, FormatExchange exchange, Document document, String reason)
            throws IOException {
        if (document == Document.PINNED) {
            return false;
        }
        LOGGER.warn("Refusing to answer the proxied enumeration {} as an empty enumeration: {}. Nothing was served; the "
                + "local 404 would have been read by the client as the upstream's own answer.", target, reason);
        exchange.respond(502);
        return true;
    }

    /**
     * What the document an ecosystem publishes a proxied artifact's digest in said about <em>that</em> artifact - the
     * integrity-surface half of the same split {@link Document} makes on the discovery surface, and the reason it is a
     * value with three states rather than a nullable {@code byte[]} with two.
     *
     * <p>{@code ProxyFormat} clause 5 lets an ecosystem that advertises no digest proxy unverified rather than
     * fabricate a check. That fall-back covers an upstream that <em>published nothing</em> - Maven serves jars whose
     * {@code .sha1} sibling is missing, Packagist leaves {@code shasum} blank for a VCS-sourced dist - and never one
     * whose declaration <em>could not be read</em>: a packument fetch that timed out, a compact index behind a
     * shared-egress {@code 429}, a registration leaf whose advertised URL the outbound screen refuses. Treating those
     * as "declares nothing" would let anyone who can drop the sidecar fetch turn integrity off for that pull - the same
     * conflation as an unreadable enumeration, one layer down.
     *
     * <p>So the three states are named and a leg returns one of them:
     * <ul>
     * <li>a <b>declared</b> digest ({@link #of} / {@link #text}) - hold the body to it and refuse a mismatch;</li>
     * <li>{@link #NONE} - <b>the document answered and declares no digest</b> for this artifact: it said {@code 404},
     *     or it was read and carries no entry, no checksum field or an unparseable one. This is clause 5's documented
     *     fall-back and is deliberately unchanged;</li>
     * <li>{@link #unreadable} - <b>the document could not be read</b>: a transport failure, any non-{@code 200} that is
     *     not a miss, a body that is not the document, a bound the read ran past, or a target the outbound screen
     *     refuses. The fill is declined ({@link #unverifiable}), so nothing is cached, the local {@code 404} stands and
     *     a later pull re-hits the upstream - which is what an unverifiable artifact deserves.</li>
     * </ul>
     *
     * @param algorithm  the {@link java.security.MessageDigest} algorithm {@code expected} is under, or the ecosystem's
     *                   own scheme name for a leg whose declaration is a composed digest string rather than a raw
     *                   digest (go's {@code h1} dirhash, reached through {@link #text}); {@code null} in both of the
     *                   other two states
     * @param expected   the bytes the artifact's digest must equal - the decoded output of {@code algorithm}, or the
     *                   UTF-8 bytes of the declaration for a {@link #text} one; {@code null} in both of the other two
     *                   states
     * @param unreadable why the declaring document could not be read, naming it, or {@code null} when it was read
     */
    public record Declared(String algorithm, byte[] expected, String unreadable) {

        /** The document answered and declares no digest for this artifact - clause 5's documented fall-back, so the
         *  fill goes ahead unverified. Not the answer for a document this repository could not read: that is
         *  {@link #unreadable}. */
        public static final Declared NONE = new Declared(null, null, null);

        /** The digest the document declares, as raw bytes under a {@link java.security.MessageDigest} algorithm. */
        public static Declared of(String algorithm, byte[] expected) {
            return new Declared(Objects.requireNonNull(algorithm, "algorithm"),
                    Objects.requireNonNull(expected, "expected").clone(), null);
        }

        /** The digest the document declares, as the ecosystem spells it - for the one leg whose declaration is a
         *  composed string rather than a raw digest (a Go {@code h1:} dirhash, which is computed by walking an archive
         *  and compared as text). Read back with {@link #text()}. */
        public static Declared text(String scheme, String declaration) {
            return of(scheme, declaration.getBytes(StandardCharsets.UTF_8));
        }

        /** The declaring document could not be read, so this fill is declined rather than downgraded to unverified.
         *  {@code reason} names the document and what happened, because that is the operator's only evidence. */
        public static Declared unreadable(String reason) {
            return new Declared(null, null, Objects.requireNonNull(reason, "reason"));
        }

        /** The declaring document could not be reached at all - the SPI's empty-{@link Optional} transport-failure
         *  sentinel, which is the exact shape an attacker who can drop one sidecar fetch produces. */
        public static Declared unreachable(URI document) {
            return unreadable("the declaring document " + document + " could not be reached");
        }

        /** Whether a digest was declared, so the fill is held to it. */
        public boolean verifiable() {
            return expected != null;
        }

        /** Whether the declaring document was read at all. {@code false} is a refusal, never a fall-back. */
        public boolean readable() {
            return unreadable == null;
        }

        /** The declaration as the ecosystem spells it, for a {@link #text} one. */
        public String text() {
            return expected == null ? null : new String(expected, StandardCharsets.UTF_8);
        }

        @Override
        public byte[] expected() {
            return expected == null ? null : expected.clone();
        }
    }

    /**
     * The verdict for a digest-declaring document the upstream answered with something other than the document. An
     * {@link #upstreamMiss upstream miss} is the origin's own answer that it publishes nothing there - a project with
     * no compact-index entry, an upstream with no registration chain - so it is {@link Declared#NONE} and the fill
     * falls back exactly as clause 5 documents. Anything else - a {@code 429} under a shared egress IP, a {@code 5xx},
     * an auth challenge - is the upstream declining to answer, so the digest is {@linkplain Declared#unreadable
     * unreadable} and the fill is declined.
     */
    public static Declared declaration(URI document, int status) {
        return upstreamMiss(status)
                ? Declared.NONE
                : Declared.unreadable("the declaring document " + document + " answered " + status);
    }

    /**
     * Fetch the small document that declares a proxied artifact's digest, buffered and
     * {@linkplain ProxyFormat.Fetcher#beside beside the artifact} - it is read to check the artifact, never served as
     * it - and fold the transport /
     * miss / refusal ladder into {@link Declared}. On a {@code 200} the caller parses
     * {@link Sidecar#document()} for the digest and returns {@link Declared#of} or {@link Declared#NONE}; otherwise it
     * returns {@link Sidecar#verdict()} unchanged, this class having already decided whether the upstream declared
     * nothing or could not be read.
     */
    public static Sidecar declaring(ProxyFormat.Fetcher fetcher, URI url, Map<String, String> requestHeaders)
            throws IOException {
        Optional<ProxyFormat.Fetched> fetched = fetcher.beside().fetch(url, requestHeaders);
        if (fetched.isEmpty()) {
            return new Sidecar(null, Declared.unreachable(url));
        }
        ProxyFormat.Fetched response = fetched.get();
        return response.status() == 200
                ? new Sidecar(response, null)
                : new Sidecar(null, declaration(url, response.status()));
    }

    /**
     * What {@link #declaring} learned. Either the document answered - {@code document} is its {@code 200} response and
     * the leg parses the digest out of it - or it did not, and {@code verdict} is the {@link Declared} the leg returns
     * unchanged.
     *
     * @param document the upstream's {@code 200} answer, or {@code null} when it did not answer
     * @param verdict  the verdict when {@link #answered()} is {@code false}; {@code null} otherwise, because a leg that
     *                 has its document reads the declaration out of it
     */
    public record Sidecar(ProxyFormat.Fetched document, Declared verdict) {

        /** Whether the declaring document answered, so the caller parses it rather than returning
         *  {@link #verdict()}. */
        public boolean answered() {
            return document != null;
        }
    }

    /**
     * Decline a cache fill whose declaring document could not be read: nothing is cached, nothing is served, the local
     * {@code 404} stands (so a later pull re-hits the upstream), and the operator is told which artifact was refused
     * and why. It never throws: a proxy leg runs after a local miss, so a throw here would surface as an unmapped
     * {@code 500} where {@link ProxyLeg}'s clause 2 says the truthful answer is the {@code 404}.
     *
     * <p>It logs at {@code WARN} because the line is the operator-visible half of the refusal.
     *
     * @return {@code false} always, so a leg reads {@code return ProxyRelay.unverifiable(...)}
     */
    public static boolean unverifiable(URI artifact, Declared declared) {
        LOGGER.warn("Refusing to fill the proxied artifact {} unverified: {}. Nothing was cached or served; the local 404 "
                + "stands so a later pull re-hits the upstream.", artifact, declared.unreadable());
        return false;
    }

    /**
     * Cache one proxied artifact under whatever its ecosystem declared for it - the three-way every
     * {@code writeVerified} leg makes, held once so the fall-back cannot widen to cover a document that was never
     * read.
     *
     * <ul>
     * <li>{@linkplain Declared#readable() unreadable} - the fill is declined through {@link #unverifiable}: nothing is
     *     stored, nothing is linked, nothing is served.</li>
     * <li>{@linkplain Declared#verifiable() declared} - the body streams into the content-addressed store under the
     *     digest and the serving pointer is linked only once it matches ({@link Blobs#writeVerified}, pointer-last);
     *     a mismatch is refused and logged, leaving an unreferenced blob rather than something that briefly
     *     served.</li>
     * <li>{@link Declared#NONE} - the document answered and declares no digest, so the body is cached unverified, which
     *     is clause 5's documented behaviour. Logged at {@code DEBUG} so an operator can still see which fills ran
     *     without a point check.</li>
     * </ul>
     *
     * <p>Either way the body is bounded ({@value ProxyArtifactSettingsContributor#LIMIT_KEY}): one that runs past the
     * bound is refused as a mismatch is, nothing cached or served, so an upstream answering with an endless body cannot
     * fill the store.
     *
     * @return {@code true} when the artifact was cached and the caller may serve it; {@code false} when the fill was
     *         refused and the local {@code 404} must stand
     */
    public static boolean fill(Blobs blobs, String key, URI artifact, InputStream body, Declared declared)
            throws IOException {
        if (!declared.readable()) {
            return unverifiable(artifact, declared);
        }
        long limit = artifactLimit();
        InputStream bounded = limit > 0 ? new Bounded(body, limit) : body;
        try {
            return write(blobs, key, artifact, bounded, declared);
        } catch (IOException | UncheckedIOException failed) {
            for (Throwable cause = failed; cause != null; cause = cause.getCause()) {
                if (cause instanceof Bounded.Exceeded) {
                    LOGGER.warn("Refusing the proxied artifact {}: it ran past the {}-byte bound ({}). Nothing was "
                            + "cached or served.", artifact, limit, ProxyArtifactSettingsContributor.LIMIT_KEY);
                    return false;
                }
            }
            throw failed;
        }
    }

    private static boolean write(Blobs blobs, String key, URI artifact, InputStream body, Declared declared)
            throws IOException {
        if (!declared.verifiable()) {
            LOGGER.debug("Nothing declares a digest for the proxied artifact {}: caching it unverified.", artifact);
            blobs.write(key, body);
            return true;
        }
        if (!blobs.writeVerified(key, body, declared.algorithm(), declared.expected())) {
            LOGGER.warn("Refusing the proxied artifact {}: its bytes do not match the {} digest {} its ecosystem declares "
                            + "for it. Nothing was cached or served.", artifact, declared.algorithm(),
                    HexFormat.of().formatHex(declared.expected()));
            return false;
        }
        return true;
    }

    /** The bound on one proxied artifact, read now: {@link ProxyArtifactSettingsContributor#LIMIT_KEY}, else its
     *  default; an unparseable or negative value is the default. */
    static long artifactLimit() {
        long fallback = Long.parseLong(ProxyArtifactSettingsContributor.LIMIT_TEXT);
        String configured = Features.lookup().apply("jenrepo." + ProxyArtifactSettingsContributor.LIMIT_KEY);
        if (configured == null || configured.isBlank()) {
            return fallback;
        }
        try {
            long limit = Long.parseLong(configured.trim());
            return limit < 0 ? fallback : limit;
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    /** A body that refuses to be read past {@code limit} bytes. */
    private static final class Bounded extends FilterInputStream {

        /** The body ran past the bound. */
        private static final class Exceeded extends IOException {

            private Exceeded(long limit) {
                super("the body ran past " + limit + " bytes");
            }
        }

        private final long limit;
        private long read;

        private Bounded(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int one = in.read();
            if (one >= 0) {
                count(1);
            }
            return one;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = in.read(buffer, offset, length);
            if (count > 0) {
                count(count);
            }
            return count;
        }

        @Override
        public long transferTo(OutputStream out) throws IOException {
            byte[] buffer = new byte[16 * 1024];
            long transferred = 0;
            int count;
            while ((count = read(buffer, 0, buffer.length)) >= 0) {
                out.write(buffer, 0, count);
                transferred += count;
            }
            return transferred;
        }

        private void count(long bytes) throws Exceeded {
            read += bytes;
            if (read > limit) {
                throw new Exceeded(limit);
            }
        }
    }

    /**
     * Stream a mutable upstream document straight through to the client, fresh on every read and never cached - the
     * shared control flow every pull-through format runs for its plain streaming leg, so a format carries only its URL,
     * its default {@code Content-Type} and its {@link Document} classification rather than re-deriving the same
     * conditional-GET dance. The client's conditional-request validators are forwarded upstream; an upstream
     * {@code 304} relays the validators and a bare {@code 304} back (so a revalidating client is not forced to re-pull
     * an unchanged index). An upstream {@link #upstreamMiss miss} returns {@code false} so the caller's local miss
     * stands; anything else is {@link #unanswered}. On a {@code 200} the body is streamed - never buffered whole - into
     * the response with the upstream's {@code Content-Length} and cache validators relayed. The response
     * {@code Content-Type} is the upstream's when it sends one, else {@code defaultContentType}; a {@code null} default
     * leaves the header unset when the upstream sent none.
     *
     * <p>This captures only the plain streaming leg. A leg that also short-circuits {@code HEAD} without a body, or
     * relays a protocol-specific header (a commit id, say), keeps that control flow in the format and reaches the same
     * verdict through {@link #unanswered} / {@link #upstreamMiss} directly.
     *
     * @return {@code true} when the request was served (a streamed {@code 200}, a relayed {@code 304}, or an
     *         {@link Document#ENUMERATION}'s {@code 502}); {@code false} to let the caller's local {@code 404} stand.
     */
    public static boolean streamFresh(ProxyFormat.Fetcher fetcher, URI url, String defaultContentType,
            FormatExchange exchange, Document document) throws IOException {
        return streamFresh(fetcher, url, defaultContentType, exchange, document, null);
    }

    /**
     * The same relay, with a {@link Tap} that reads the body as it passes.
     *
     * <p>For the leg that must LEARN something from a document it does not otherwise parse - Debian's {@code Packages}
     * index, whose per-package digests are the only place a pool {@code .deb}'s checksum is published. The document
     * is still streamed to the client, never buffered in memory, and at the upstream's pace: the bytes are copied to
     * a spool file on their way, and the tap reads that file once the response is complete, so a tap that writes a
     * record per stanza costs the client nothing. The relay returns when the tap has read the body, so what it
     * records is there for the request that follows. A body that did not reach the client whole is not read.
     *
     * <p>A tap that throws does NOT fail the relay. What it records is an optimisation of a later request, while the
     * response is a client's read - failing that because a side task failed would turn a bookkeeping problem into an
     * outage. The failure is logged.
     */
    public static boolean streamFresh(ProxyFormat.Fetcher fetcher, URI url, String defaultContentType,
            FormatExchange exchange, Document document, Tap tap) throws IOException {
        return relay(fetcher, url, defaultContentType, exchange, document, tap, null);
    }

    /**
     * {@link #streamFresh}, answered from the node's {@link UpstreamMemory} for {@code repository} while it remembers
     * the document, and remembering a {@code 200} small enough to keep: the next read within the memory's ttl costs
     * the upstream nothing, and a client revalidating with the remembered {@code ETag} is answered {@code 304} here.
     * A document past the memory's entry cap streams through uncached, as {@link #streamFresh} streams it.
     *
     * <p>For a document a client reads alone. A document another one names by digest - a Debian {@code Packages}
     * its {@code InRelease} lists - must not be remembered apart from the one that names it, or a root remembered
     * at one moment names a member fetched after the upstream moved; such a family is relayed fresh.
     */
    public static boolean streamRemembered(ProxyFormat.Fetcher fetcher, URI url, String defaultContentType,
            FormatExchange exchange, Document document, ArtifactStore repository) throws IOException {
        Objects.requireNonNull(repository, "repository");
        Optional<UpstreamMemory.Remembered> remembered = UpstreamMemory.node().get(repository, url);
        if (remembered.isPresent()) {
            answer(remembered.get(), defaultContentType, exchange);
            return true;
        }
        return relay(fetcher, url, defaultContentType, exchange, document, null, repository);
    }

    /** Answer a remembered document: {@code 304} to a client whose {@code If-None-Match} names its {@code ETag},
     *  else the document with its {@code Content-Type} and validators. */
    private static void answer(UpstreamMemory.Remembered remembered, String defaultContentType,
            FormatExchange exchange) throws IOException {
        String etag = remembered.headers().get("ETag");
        relay(etag, remembered.headers().get("Last-Modified"), exchange);
        String ifNoneMatch = exchange.requestHeader("If-None-Match");
        if (etag != null && ifNoneMatch != null && ifNoneMatch.contains(etag)) {
            exchange.respond(304);
            return;
        }
        String contentType = remembered.headers().getOrDefault("Content-Type", defaultContentType);
        if (contentType != null) {
            exchange.setResponseHeader("Content-Type", contentType);
        }
        exchange.respond(200, remembered.body());
    }

    private static boolean relay(ProxyFormat.Fetcher fetcher, URI url, String defaultContentType,
            FormatExchange exchange, Document document, Tap tap, ArtifactStore remembering) throws IOException {
        try (ProxyFormat.Download download = fetcher.download(url, conditionalHeaders(exchange)).orElse(null)) {
            if (download == null) {
                return unanswered(url, exchange, document, "the upstream could not be reached");
            }
            if (download.status() == 304) {
                relayValidators(download, exchange);
                exchange.respond(304);
                return true;
            }
            if (upstreamMiss(download.status())) {
                return false;
            }
            if (download.status() != 200) {
                return unanswered(url, exchange, document, "the upstream answered " + download.status());
            }
            String contentType = download.header("Content-Type");
            if (contentType != null) {
                exchange.setResponseHeader("Content-Type", contentType);
            } else if (defaultContentType != null) {
                exchange.setResponseHeader("Content-Type", defaultContentType);
            }
            relayValidators(download, exchange);
            if (remembering != null) {
                // Small enough to remember: read whole, remembered and served. Larger: the head already read and the
                // rest stream on, uncached.
                byte[] head = download.body().readNBytes(UpstreamMemory.ENTRY_CAP + 1);
                if (head.length <= UpstreamMemory.ENTRY_CAP) {
                    UpstreamMemory.node().put(remembering, url, head, download::header);
                    exchange.respond(200, head);
                    return true;
                }
                try (OutputStream out = exchange.respond(200, length(download.header("Content-Length")))) {
                    out.write(head);
                    download.body().transferTo(out);
                }
                return true;
            }
            if (tap == null) {
                try (OutputStream out = exchange.respond(200, length(download.header("Content-Length")))) {
                    download.body().transferTo(out);
                }
                return true;
            }
            Path spool = OwnerOnly.createTempFile("jenrepo-relay-tap", ".body");
            try {
                boolean spooled;
                try (OutputStream out = exchange.respond(200, length(download.header("Content-Length")))) {
                    spooled = tee(download.body(), out, spool, url);
                }
                if (spooled) {
                    try (InputStream body = Files.newInputStream(spool)) {
                        tap.read(body);
                    } catch (IOException | RuntimeException failed) {
                        LOGGER.warn("Reading {} after it was relayed failed; the response is unaffected", url, failed);
                    }
                }
            } finally {
                Files.deleteIfExists(spool);
            }
            return true;
        }
    }

    /** A reader of a relayed body, fed the whole body once the client has it. */
    @FunctionalInterface
    public interface Tap {

        /** Read {@code body} to its end. What is learned is the tap's business; the relay only guarantees the bytes. */
        void read(InputStream body) throws IOException;
    }

    /**
     * Stream the body to the client and copy it to {@code spool} on the way. A failure to write the client's copy
     * propagates; a failure to write the spool stops the spooling and lets the client's copy finish.
     *
     * @return whether the spool holds the whole body
     */
    private static boolean tee(InputStream body, OutputStream out, Path spool, URI url) throws IOException {
        OutputStream copy = Files.newOutputStream(spool);
        boolean spooling = true;
        try {
            byte[] buffer = new byte[16 * 1024];
            for (int read = body.read(buffer); read >= 0; read = body.read(buffer)) {
                out.write(buffer, 0, read);
                if (spooling) {
                    try {
                        copy.write(buffer, 0, read);
                    } catch (IOException full) {
                        LOGGER.warn("Copying {} for a reader failed; the response is unaffected", url, full);
                        spooling = false;
                    }
                }
            }
        } finally {
            try {
                copy.close();
            } catch (IOException unflushed) {
                spooling = false;
            }
        }
        return spooling;
    }

    /**
     * Fetch a small upstream document buffered - one a leg has to parse or rewrite before serving - and reach the same
     * verdict {@link #streamFresh} does for the streaming shape. The buffered twin exists because most enumeration
     * documents are rewritten on the way out (an npm packument's {@code dist.tarball}, a Composer {@code dist.url}, a
     * PEP 503 {@code href}), so the leg cannot hand the whole serve to this class - but it can, and must, hand it the
     * decision.
     *
     * @return an {@link Answer} carrying the {@code 200} document when the upstream answered the question, so the leg
     *         rewrites and serves it; otherwise an unanswered one whose {@link Answer#served()} is the value
     *         {@code pullThrough} must return, this class having already written the {@code 304} or the {@code 502}.
     */
    public static Answer fetchFresh(ProxyFormat.Fetcher fetcher, URI url, Map<String, String> requestHeaders,
            FormatExchange exchange, Document document) throws IOException {
        Optional<ProxyFormat.Fetched> fetched = fetcher.fetch(url, requestHeaders);
        if (fetched.isEmpty()) {
            return new Answer(null, unanswered(url, exchange, document, "the upstream could not be reached"));
        }
        ProxyFormat.Fetched response = fetched.get();
        if (response.status() == 304) {
            relayValidators(response, exchange);
            exchange.respond(304);
            return new Answer(null, true);
        }
        if (upstreamMiss(response.status())) {
            return new Answer(null, false);
        }
        if (response.status() != 200) {
            return new Answer(null,
                    unanswered(url, exchange, document, "the upstream answered " + response.status()));
        }
        return new Answer(response, true);
    }

    /**
     * {@link #fetchFresh}, answered from the node's {@link UpstreamMemory} for {@code repository} while it remembers
     * the document, and remembering a {@code 200} it fetches: the leg rewrites and serves the answer exactly as it
     * would a fresh one. A client revalidating with the remembered {@code ETag} is answered {@code 304} here. The same
     * caution as {@link #streamRemembered} holds for a document another names by digest.
     */
    public static Answer fetchRemembered(ProxyFormat.Fetcher fetcher, URI url, Map<String, String> requestHeaders,
            FormatExchange exchange, Document document, ArtifactStore repository) throws IOException {
        Objects.requireNonNull(repository, "repository");
        UpstreamMemory memory = UpstreamMemory.node();
        Optional<UpstreamMemory.Remembered> remembered = memory.get(repository, url);
        if (remembered.isPresent()) {
            String etag = remembered.get().headers().get("ETag");
            String ifNoneMatch = exchange.requestHeader("If-None-Match");
            if (etag != null && ifNoneMatch != null && ifNoneMatch.contains(etag)) {
                relay(etag, remembered.get().headers().get("Last-Modified"), exchange);
                exchange.respond(304);
                return new Answer(null, true);
            }
            return new Answer(new ProxyFormat.Fetched(200, remembered.get().body(), remembered.get().headers()),
                    true);
        }
        Answer answer = fetchFresh(fetcher, url, requestHeaders, exchange, document);
        if (answer.answered()) {
            memory.put(repository, url, answer.document().body(), answer.document()::header);
        }
        return answer;
    }

    /**
     * What {@link #fetchFresh} learned. Either the upstream answered the question - {@code document} is its
     * {@code 200} response and the leg goes on to rewrite and serve it - or it did not, and {@code served} is the
     * value the leg returns unchanged, the response (a relayed {@code 304}, an enumeration's {@code 502}, or nothing
     * at all for a miss) having already been decided.
     *
     * @param document the upstream's {@code 200} answer, or {@code null} when it did not answer the question
     * @param served   what {@code pullThrough} must return when {@link #answered()} is {@code false}; meaningless
     *                 otherwise, because a leg that has its document serves it itself
     */
    public record Answer(ProxyFormat.Fetched document, boolean served) {

        /** Whether the upstream answered with the document, so the caller serves it rather than returning
         *  {@link #served()}. */
        public boolean answered() {
            return document != null;
        }
    }
}
