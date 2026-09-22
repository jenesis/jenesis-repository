package build.jenesis.repository.blobs;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;


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
     * <p>The distinction is the earlier. {@link ProxyFormat} clause 2 makes a single {@code false} - "let the local
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
         * {@code 502} instead of being answered "none" (&sect;5, &sect;9).
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
     * <p>That lie is not hypothetical, which is why this is a seam and not a per-leg line. On a loaded full build one
     * Go {@code @v/list} fetch ran 5.6 s - the shipped transport's connect timeout is five seconds, and it reports a
     * timeout as the empty result - and {@code github.com/pkg/errors} was served to the client as a module with no
     * versions. The {@code 404} was then investigated as an enumeration regression in the newly paged asset walk,
     * because a network blip and a real absence are the same bytes on the wire. All thirteen proxying formats
     * carried the hole, and so did the Maven leg; the fix lives here
     * rather than thirteen times over so a fifteenth format cannot reopen it by writing the obvious
     * {@code return false}.
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
     * <p>{@code ProxyFormat} clause 5 licenses one fall-back and states the reason for it: "an ecosystem that
     * advertises no digest proxies unverified rather than fabricating a check". That reasoning was written for the
     * upstream having <em>published nothing</em> - Maven serves jars whose {@code .sha1} sibling is missing, Packagist
     * leaves {@code shasum} blank for a VCS-sourced dist - and every leg that resolves a digest out of a second
     * document was applying it to a third case the clause never covered: <em>we could not read what it published</em>.
     * A packument fetch that timed out, a compact index behind a shared-egress {@code 429}, a registration leaf whose
     * advertised URL the outbound screen refuses - each of them returned "this ecosystem declares no checksum for this
     * artifact", and the artifact was then cached with an unverified write. Anyone who can drop the sidecar fetch turns
     * integrity off for that pull, and clause 5's "held to it and a mismatch is refused" quietly does not run (,
     * the one-layer-down twin of).
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
     * Fetch the small document that declares a proxied artifact's digest, buffered, and fold the transport /
     * miss / refusal ladder every leg was writing out by hand into {@link Declared}. On a {@code 200} the caller parses
     * {@link Sidecar#document()} for the digest and returns {@link Declared#of} or {@link Declared#NONE}; otherwise it
     * returns {@link Sidecar#verdict()} unchanged, this class having already decided whether the upstream declared
     * nothing or could not be read.
     */
    public static Sidecar declaring(ProxyFormat.Fetcher fetcher, URI url, Map<String, String> requestHeaders)
            throws IOException {
        Optional<ProxyFormat.Fetched> fetched = fetcher.fetch(url, requestHeaders);
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
     * Decline a cache fill whose declaring document could not be read - the refusal shape established for a proxy
     * leg and the one this whole split exists to reach: nothing is cached, nothing is served, the local {@code 404}
     * stands (so a later pull re-hits the upstream), and the operator is told which artifact was refused and why. It
     * never throws: a proxy leg runs after a local miss, so a throw here would surface as an unmapped {@code 500} where
     * {@link ProxyLeg}'s clause 2 says the truthful answer is the {@code 404}.
     *
     * <p>It is a {@code WARN} rather than a {@code DEBUG} because it is the operator-visible half of the fix. Two legs
     * already logged this exact downgrade at {@code DEBUG} while still caching the artifact - the evidence was there
     * and said nothing, since a line nobody reads beside a fill that happened anyway is not a refusal.
     *
     * @return {@code false} always, so a leg reads {@code return ProxyRelay.unverifiable(...)}
     */
    public static boolean unverifiable(URI artifact, Declared declared) {
        LOGGER.warn("Refusing to fill the proxied artifact {} unverified: {}. Nothing was cached or served; the local 404 "
                + "stands so a later pull re-hits the upstream.", artifact, declared.unreadable());
        return false;
    }

    /**
     * Cache one proxied artifact under whatever its ecosystem declared for it - the shared three-way every
     * {@code writeVerified} leg makes, held once so the fall-back cannot quietly widen to cover a document that was
     * never read.
     *
     * <ul>
     * <li>{@linkplain Declared#readable() unreadable} - the fill is declined through {@link #unverifiable}: nothing is
     *     stored, nothing is linked, nothing is served.</li>
     * <li>{@linkplain Declared#verifiable() declared} - the body streams into the content-addressed store under the
     *     digest and the serving pointer is linked only once it matches ({@link Blobs#writeVerified}, pointer-last);
     *     a mismatch is refused and logged, leaving an unreferenced blob rather than something that briefly served.</li>
     * <li>{@link Declared#NONE} - the document answered and declares no digest, so the body is cached unverified, which
     *     is clause 5's documented behaviour. Logged at {@code DEBUG} so an operator can still see which fills ran
     *     without a point check.</li>
     * </ul>
     *
     * @return {@code true} when the artifact was cached and the caller may serve it; {@code false} when the fill was
     *         refused and the local {@code 404} must stand
     */
    public static boolean fill(Blobs blobs, String key, URI artifact, InputStream body, Declared declared)
            throws IOException {
        if (!declared.readable()) {
            return unverifiable(artifact, declared);
        }
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
     * is still streamed, never buffered: the tap sees the same bytes on their way to the client, so a 45 MB index
     * costs the reader's own bounded state and nothing more.
     *
     * <p>A tap that throws does NOT fail the relay. What it records is an optimisation of a later request, while the
     * response in flight is a client's read - failing that because a side task could not keep up would turn a
     * bookkeeping problem into an outage. The failure is logged and the stream completes.
     */
    public static boolean streamFresh(ProxyFormat.Fetcher fetcher, URI url, String defaultContentType,
            FormatExchange exchange, Document document, Tap tap) throws IOException {
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
            try (OutputStream out = exchange.respond(200, length(download.header("Content-Length")))) {
                if (tap == null) {
                    download.body().transferTo(out);
                } else {
                    tap(download.body(), out, tap, url);
                }
            }
            return true;
        }
    }

    /** A reader of a relayed body, fed the bytes as they stream to the client. */
    @FunctionalInterface
    public interface Tap {

        /** Read {@code body} to its end. What is learned is the tap's business; the relay only guarantees the bytes. */
        void read(InputStream body) throws IOException;
    }

    /** Stream the body to the client while a tap reads the same bytes, on a thread of its own so neither waits on
     *  the other's pace beyond one pipe buffer. */
    private static void tap(InputStream body, OutputStream out, Tap tap, URI url) throws IOException {
        PipedOutputStream feed = new PipedOutputStream();
        PipedInputStream copy = new PipedInputStream(feed, 64 * 1024);
        Thread reader = Thread.ofVirtual().start(() -> {
            try (InputStream in = copy) {
                tap.read(in);
            } catch (IOException | RuntimeException failed) {
                LOGGER.warn("Reading {} while it streamed failed; the response is unaffected", url, failed);
            }
        });
        try (OutputStream fed = feed) {
            byte[] buffer = new byte[16 * 1024];
            for (int read = body.read(buffer); read >= 0; read = body.read(buffer)) {
                out.write(buffer, 0, read);
                try {
                    fed.write(buffer, 0, read);
                } catch (IOException tapGone) {
                    // The tap stopped reading (it had what it needed, or it failed): the client's copy continues.
                    break;
                }
            }
            body.transferTo(out);
        } finally {
            try {
                reader.join(Duration.ofSeconds(30));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
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
